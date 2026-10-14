package cn.android666.im.ui.chat

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.common.UuidIdGenerator
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.message.MessageJson
import cn.android666.im.data.message.MessageRepository
import cn.android666.im.data.sync.MamPager
import cn.android666.im.data.sync.HistoryLoader
import cn.android666.im.data.message.IncomingMessageHandler
import cn.android666.im.data.message.MessageState
import cn.android666.im.data.message.ReadMarkers
import cn.android666.im.data.message.Retractions
import cn.android666.im.data.message.SendQueue
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.testing.FakeArchive
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeXmppClient
import cn.android666.im.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ChatViewModel] 测试：分页消息与时间分隔、发送、标题、可见时清除未读 */
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val client = FakeXmppClient()
    private lateinit var holder: UserDatabaseHolder
    private lateinit var db: ImDatabase

    @Before
    fun setUp() {
        holder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(mainDispatcherRule.testDispatcher)
                .allowMainThreadQueries()
                .build()
        }
        db = holder.open("10001")
    }

    @After
    fun tearDown() = holder.close()

    /** 被测对象与依赖 */
    private fun TestScope.newVm(kind: ConversationKind = ConversationKind.SINGLE, id: String = BOB): Pair<ChatViewModel, MessageRepository> {
        val clock = FakeClock(startMillis = 10_000_000)
        val queue = SendQueue(client, holder, backgroundScope, clock)
        val messages = MessageRepository(holder, queue, ServerClock(clock), UuidIdGenerator()) { "10001@im.example.com" }
        val conversations = ConversationRepository(holder)
        val readMarkers = ReadMarkers(client, messages, conversations, UuidIdGenerator())
        val retractions = Retractions(client, messages, ServerClock(clock), UuidIdGenerator(), { "10001@im.example.com" }, selfName = { null })
        val vm = ChatViewModel(id, kind, messages, conversations, readMarkers, retractions, OrgRepository(client, holder, clock))
        return vm to messages
    }

    private suspend fun insert(id: String, time: Long, direction: Int = MessageEntity.DIRECTION_IN) = db.messageDao().insert(
        MessageEntity(
            msgId = id, conversationId = BOB, senderJid = if (direction == MessageEntity.DIRECTION_IN) BOB else "10001@im.example.com",
            direction = direction, type = "text", body = "b-$id", status = MessageEntity.STATUS_RECEIVED, serverTime = time,
        ),
    )

    /** 新的在前；间隔超过 5 分钟插入时间分隔；加载到最早一条时在它上方也插入 */
    @Test
    fun items_withTimeHeaders() = runTest(mainDispatcherRule.testDispatcher) {
        db.employeeDao().upsert(listOf(EmployeeEntity(BOB, "10002", "李四", "lisi", "ls", "d", "", "", "", "", 0, "h")))
        insert("old", 0)
        insert("mid", 60_000)
        insert("new", 60_000 + ChatTimeline.GAP_MILLIS + 1)
        val (vm, _) = newVm()

        val items = vm.items.asSnapshot()

        assertThat(items.map { it.key }).containsExactly("m3", "t3", "m2", "m1", "t1").inOrder()
        val newest = (items[0] as ChatListItem.Message).message
        assertThat(newest.senderName).isEqualTo("李四")
        assertThat(newest.senderAvatarHash).isEqualTo("h")
        assertThat(newest.state).isEqualTo(MessageState.RECEIVED)
    }

    /** 分页：超过一页时向上滑动加载更早的消息 */
    @Test
    fun items_paging() = runTest(mainDispatcherRule.testDispatcher) {
        repeat(100) { insert("m$it", it * 1_000L) }
        val (vm, _) = newVm()

        val firstPage = vm.items.asSnapshot()
        assertThat(firstPage.size).isLessThan(100)

        val all = vm.items.asSnapshot { appendScrollWhile { true } }
        assertThat(all.filterIsInstance<ChatListItem.Message>()).hasSize(100)
    }

    @Test
    fun send_emitsSentAndAppears() = runTest(mainDispatcherRule.testDispatcher) {
        val (vm, _) = newVm()

        vm.events.test {
            vm.send("你好")
            runCurrent()
            assertThat(awaitItem()).isEqualTo(ChatEvent.Sent)
        }
        val message = (vm.items.asSnapshot().first() as ChatListItem.Message).message
        assertThat(message.body).isEqualTo("你好")
        assertThat(message.outgoing).isTrue()
        // 未登录：保持发送中
        assertThat(message.state).isEqualTo(MessageState.SENDING)
        assertThat(db.conversationDao().get(BOB)!!.type).isEqualTo(ConversationEntity.TYPE_SINGLE)
    }

    @Test
    fun send_blankIgnored_tooLongToast() = runTest(mainDispatcherRule.testDispatcher) {
        val (vm, _) = newVm()

        vm.events.test {
            vm.send("   ")
            vm.send("a".repeat(MessageRepository.MAX_TEXT_LENGTH + 1))
            assertThat(awaitItem()).isEqualTo(ChatEvent.Toast(R.string.chat_text_too_long))
            runCurrent()
            expectNoEvents()
        }
        assertThat(db.messageDao().last(BOB)).isNull()
    }

    /** 文件传输助手：会话类型为 TYPE_SELF；没有对方姓名 */
    @Test
    fun selfConversation() = runTest(mainDispatcherRule.testDispatcher) {
        val (vm, _) = newVm(ConversationKind.SELF, "10001@im.example.com")

        vm.send("备忘")
        runCurrent()

        assertThat(db.conversationDao().get("10001@im.example.com")!!.type).isEqualTo(ConversationEntity.TYPE_SELF)
        vm.peerName.test { assertThat(awaitItem()).isNull() }
    }

    @Test
    fun peerName_fromOrg() = runTest(mainDispatcherRule.testDispatcher) {
        db.employeeDao().upsert(listOf(EmployeeEntity(BOB, "10002", "李四", "lisi", "ls", "d", "", "", "", "", 0, "")))
        val (vm, _) = newVm()

        vm.peerName.test {
            assertThat(awaitItem()).isNull()
            assertThat(awaitItem()).isEqualTo("李四")
        }
    }

    /** 页面可见：清除未读，该会话为正在查看；不可见后取消 */
    @Test
    fun visibility_clearsUnreadAndActive() = runTest(mainDispatcherRule.testDispatcher) {
        db.conversationDao().upsert(ConversationEntity(BOB, ConversationEntity.TYPE_SINGLE, unreadCount = 4))
        val (vm, messages) = newVm()

        vm.onVisible()
        runCurrent()
        assertThat(db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(0)
        assertThat(messages.activeConversationId()).isEqualTo(BOB)

        vm.onHidden()
        assertThat(messages.activeConversationId()).isNull()
    }

    /** 重发：失败的消息回到发送中 */
    @Test
    fun resend() = runTest(mainDispatcherRule.testDispatcher) {
        val row = db.messageDao().insert(
            MessageEntity(
                msgId = "f", conversationId = BOB, senderJid = "10001@im.example.com", direction = MessageEntity.DIRECTION_OUT,
                type = "text", body = "x", status = MessageEntity.STATUS_FAILED, serverTime = 1,
            ),
        )
        val (vm, _) = newVm()

        vm.resend(row)
        runCurrent()

        assertThat(db.messageDao().getByRowId(row)!!.status).isEqualTo(MessageEntity.STATUS_SENDING)
    }

    /** 长按菜单：文字可复制；未撤回的可引用、转发；都可多选、删除；本人 2 分钟内已发出的可撤回 */
    @Test
    fun actionsFor_rules() = runTest(mainDispatcherRule.testDispatcher) {
        val (vm, _) = newVm()
        fun msg(outgoing: Boolean, state: MessageState, time: Long, retracted: Boolean = false) = cn.android666.im.data.message.ChatMessage(
            rowId = 1, msgId = "m", conversationId = BOB, outgoing = outgoing, senderJid = "s", senderName = null, senderAvatarHash = "",
            content = cn.android666.im.core.xmpp.extension.msg.MessageContent.Text, body = "x", state = state, errorCode = null,
            serverTime = time, replyToMsgId = null, retracted = retracted, retractedBy = null,
        )
        val now = 1_000_000L
        val copy = MessageActionsDialogFragment.ACTION_COPY
        val reply = MessageActionsDialogFragment.ACTION_REPLY
        val forward = MessageActionsDialogFragment.ACTION_FORWARD
        val select = MessageActionsDialogFragment.ACTION_SELECT
        val retract = MessageActionsDialogFragment.ACTION_RETRACT
        val delete = MessageActionsDialogFragment.ACTION_DELETE
        assertThat(vm.actionsFor(msg(true, MessageState.SENT, now - 60_000), now))
            .containsExactly(copy, reply, forward, select, retract, delete).inOrder()
        val common = listOf(copy, reply, forward, select, delete)
        assertThat(vm.actionsFor(msg(true, MessageState.READ, now - 121_000), now)).containsExactlyElementsIn(common).inOrder()
        assertThat(vm.actionsFor(msg(true, MessageState.FAILED, now), now)).containsExactlyElementsIn(common).inOrder()
        assertThat(vm.actionsFor(msg(true, MessageState.SENDING, now), now)).containsExactlyElementsIn(common).inOrder()
        assertThat(vm.actionsFor(msg(false, MessageState.RECEIVED, now), now)).containsExactlyElementsIn(common).inOrder()
        assertThat(vm.actionsFor(msg(true, MessageState.SENT, now, retracted = true), now)).containsExactly(select, delete).inOrder()
    }

    /** 4.15：引用对方的消息 → 引用条显示姓名与原文 → 发送后带 reply，引用条清空；列表中显示引用预览 */
    @Test
    fun reply_sendAndPreview() = runTest(mainDispatcherRule.testDispatcher) {
        db.employeeDao().upsert(listOf(EmployeeEntity(BOB, "10002", "李四", "lisi", "ls", "d", "", "", "", "", 0, "")))
        val orig = insert("orig", 1_000)
        val (vm, _) = newVm()

        vm.startReply(orig)
        runCurrent()
        assertThat(vm.reply.value).isEqualTo(ReplyDraft("orig", BOB, "李四", "b-orig"))

        vm.send("收到")
        runCurrent()
        assertThat(vm.reply.value).isNull()
        val sent = db.messageDao().last(BOB)!!
        assertThat(sent.replyToMsgId).isEqualTo("orig")

        val newest = (vm.items.asSnapshot().first() as ChatListItem.Message).message
        assertThat(newest.reply).isEqualTo(cn.android666.im.data.message.ReplyPreview.Found(BOB, "李四", "b-orig"))
    }

    /** 引用预览：原消息不在本地 → 不可见；原消息已撤回 → 已撤回 */
    @Test
    fun replyPreview_missingAndRetracted() = runTest(mainDispatcherRule.testDispatcher) {
        val gone = insert("gone", 1_000)
        db.messageDao().markRetracted(gone, BOB)
        db.messageDao().insert(
            MessageEntity(msgId = "r1", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "text",
                body = "x", status = MessageEntity.STATUS_RECEIVED, serverTime = 2_000, replyToMsgId = "gone"),
        )
        db.messageDao().insert(
            MessageEntity(msgId = "r2", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "text",
                body = "y", status = MessageEntity.STATUS_RECEIVED, serverTime = 3_000, replyToMsgId = "never-seen"),
        )
        val (vm, _) = newVm()

        val messages = vm.items.asSnapshot().filterIsInstance<ChatListItem.Message>().associate { it.message.msgId to it.message.reply }
        assertThat(messages["r1"]).isEqualTo(cn.android666.im.data.message.ReplyPreview.Retracted)
        assertThat(messages["r2"]).isEqualTo(cn.android666.im.data.message.ReplyPreview.Missing)
        assertThat(messages["gone"]).isNull()
    }

    /** 引用自己的消息：姓名为空（界面显示"我"）；已撤回的不能引用；取消引用 */
    @Test
    fun reply_ownAndCancel() = runTest(mainDispatcherRule.testDispatcher) {
        val own = insert("own", 1_000, direction = MessageEntity.DIRECTION_OUT)
        val gone = insert("gone", 2_000)
        db.messageDao().markRetracted(gone, BOB)
        val (vm, _) = newVm()

        vm.startReply(gone)
        runCurrent()
        assertThat(vm.reply.value).isNull()

        vm.startReply(own)
        runCurrent()
        assertThat(vm.reply.value!!.senderName).isNull()
        vm.cancelReply()
        assertThat(vm.reply.value).isNull()
    }

    /** 4.17 多选：进入、切换、上限 100、退出 */
    @Test
    fun selection() = runTest(mainDispatcherRule.testDispatcher) {
        val (vm, _) = newVm()

        vm.toggleSelection(1)
        assertThat(vm.selection.value).isNull()

        vm.startSelection(1)
        vm.toggleSelection(2)
        vm.toggleSelection(1)
        assertThat(vm.selection.value).containsExactly(2L)

        vm.events.test {
            (3L..102L).forEach(vm::toggleSelection)
            assertThat(vm.selection.value).hasSize(100)
            assertThat(awaitItem()).isEqualTo(ChatEvent.Toast(R.string.chat_selection_limit))
            cancelAndIgnoreRemainingEvents()
        }

        vm.cancelSelection()
        assertThat(vm.selection.value).isNull()
    }

    /** 4.17 本地删除：消息删除、退出多选；删除的是最后一条时预览改为剩下的最后一条 */
    @Test
    fun deleteLocal_updatesPreview() = runTest(mainDispatcherRule.testDispatcher) {
        val first = insert("m1", 1_000)
        val last = insert("m2", 2_000)
        db.conversationDao().upsert(ConversationEntity(BOB, ConversationEntity.TYPE_SINGLE, last, "b-m2", 2_000))
        val (vm, _) = newVm()

        vm.startSelection(last)
        vm.delete(setOf(last))
        runCurrent()

        assertThat(db.messageDao().get(BOB, "m2")).isNull()
        assertThat(vm.selection.value).isNull()
        val conversation = db.conversationDao().get(BOB)!!
        assertThat(conversation.lastMsgRowId).isEqualTo(first)
        assertThat(conversation.lastMsgPreview).isEqualTo("b-m1")

        // 删除最后剩下的：预览清空，会话不再显示在列表中
        vm.delete(setOf(first))
        runCurrent()
        assertThat(db.conversationDao().get(BOB)!!.lastMsgRowId).isNull()
        assertThat(ConversationRepository(holder).observeConversations().first()).isEmpty()
    }

    /** 4.16 逐条转发：每条生成新消息（新 ID、内容相同），跳过已撤回的；退出多选并提示 */
    @Test
    fun forward_each() = runTest(mainDispatcherRule.testDispatcher) {
        val card = MessageContent.Card("10003@im.example.com", "王五")
        val a = insert("a", 1_000)
        val b = db.messageDao().insert(
            MessageEntity(msgId = "b", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = card.type,
                body = "[名片] 王五", contentJson = MessageJson.encodeContent(card),
                status = MessageEntity.STATUS_RECEIVED, serverTime = 2_000),
        )
        val gone = insert("gone", 3_000)
        db.messageDao().markRetracted(gone, BOB)
        val (vm, _) = newVm()
        vm.startSelection(a)

        vm.events.test {
            vm.forward(cn.android666.im.ui.forward.ForwardTarget(CAROL, ConversationKind.SINGLE, "王五", ""), setOf(a, b, gone), merged = false)
            runCurrent()
            assertThat(awaitItem()).isEqualTo(ChatEvent.Toast(R.string.forward_done))
        }
        assertThat(vm.selection.value).isNull()
        val forwarded = db.messageDao().getByRowIds((1L..20L).toList()).filter { it.conversationId == CAROL }
        assertThat(forwarded.map { it.body }).containsExactly("b-a", "[名片] 王五").inOrder()
        assertThat(forwarded.map { it.msgId }).containsNoneOf("a", "b")
        assertThat(forwarded.all { it.direction == MessageEntity.DIRECTION_OUT && it.status == MessageEntity.STATUS_SENDING }).isTrue()
        assertThat(cn.android666.im.data.message.MessageJson.decodeContent(forwarded[1].type, forwarded[1].contentJson)).isEqualTo(card)
        assertThat(db.conversationDao().get(CAROL)!!.type).isEqualTo(ConversationEntity.TYPE_SINGLE)
    }

    /** 4.16 合并转发：一条 merged 消息，标题为"张三和李四的聊天记录"，条目含发送方、正文、时间 */
    @Test
    fun forward_merged() = runTest(mainDispatcherRule.testDispatcher) {
        db.employeeDao().upsert(
            listOf(
                EmployeeEntity("10001@im.example.com", "10001", "张三", "zhangsan", "zs", "d", "", "", "", "", 0, ""),
                EmployeeEntity(BOB, "10002", "李四", "lisi", "ls", "d", "", "", "", "", 0, ""),
            ),
        )
        val a = insert("a", 1_000)
        val b = insert("b", 2_000, direction = MessageEntity.DIRECTION_OUT)
        val (vm, _) = newVm()

        vm.forward(cn.android666.im.ui.forward.ForwardTarget("10001@im.example.com", ConversationKind.SELF, null, ""), setOf(b, a), merged = true)
        runCurrent()

        val sent = db.messageDao().last("10001@im.example.com")!!
        assertThat(sent.body).isEqualTo("[聊天记录] 张三和李四的聊天记录")
        val content = cn.android666.im.data.message.MessageJson.decodeContent(sent.type, sent.contentJson)
            as cn.android666.im.core.xmpp.extension.msg.MessageContent.Merged
        assertThat(content.title).isEqualTo("张三和李四的聊天记录")
        assertThat(content.items.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(content.items[0].from).isEqualTo(BOB)
        assertThat(content.items[0].stamp).isEqualTo(1_000)
        assertThat(db.conversationDao().get("10001@im.example.com")!!.type).isEqualTo(ConversationEntity.TYPE_SELF)
    }

    /** 撤回未发出（未登录时一直是发送中）的消息：不允许，不提示 */
    @Test
    fun retract_notAllowed_silent() = runTest(mainDispatcherRule.testDispatcher) {
        val (vm, _) = newVm()
        vm.send("x")
        runCurrent()
        val rowId = db.messageDao().last(BOB)!!.rowId

        vm.events.test {
            // 前面发送产生的事件
            assertThat(awaitItem()).isEqualTo(ChatEvent.Sent)
            vm.retract(rowId)
            runCurrent()
            expectNoEvents()
        }
    }

    /** 4.11：本地没有消息时，向上滑动从服务器分页加载全部历史；时间分隔照常插入 */
    @Test
    fun items_loadHistoryFromServer() = runTest(mainDispatcherRule.testDispatcher) {
        val archive = FakeArchive(client)
        client.iqResponder = archive.responder()
        repeat(120) {
            archive.add(
                10_000_000L + it * 60_000L,
                "<message xmlns='jabber:client' type='chat' id='h$it' from='$BOB/pc' to='10001@im.example.com'><body>历史 $it</body></message>",
            )
        }
        val clock = FakeClock(startMillis = 20_000_000)
        val queue = SendQueue(client, holder, backgroundScope, clock)
        val selfJid = { "10001@im.example.com" }
        val messages = MessageRepository(holder, queue, ServerClock(clock), UuidIdGenerator(), selfJid)
        val conversations = ConversationRepository(holder)
        val readMarkers = ReadMarkers(client, messages, conversations, UuidIdGenerator())
        val retractions = Retractions(client, messages, ServerClock(clock), UuidIdGenerator(), selfJid, selfName = { null })
        val handler = IncomingMessageHandler(client, messages, readMarkers, retractions, ServerClock(clock), UuidIdGenerator(), selfJid, backgroundScope)
        val history = HistoryLoader(MamPager(client), handler, holder, UuidIdGenerator())
        val vm = ChatViewModel(BOB, ConversationKind.SINGLE, messages, conversations, readMarkers, retractions, OrgRepository(client, holder, clock), history)

        val items = vm.items.asSnapshot { appendScrollWhile { true } }

        // 全部历史都写入了本地库（每页 50 条，3 次查询）
        assertThat(db.messageDao().countIncomingAfter(BOB, -1)).isEqualTo(120)
        assertThat(archive.queries.size).isAtLeast(3)
        // 快照是滚动窗口：每次写库后分页按滚动位置（较早的一端）重新加载，较新的一端在往回滚动时再加载；
        // 这里检查窗口按时间倒序连续，并且已到达最早一条（其上方有时间分隔）
        val loaded = items.filterIsInstance<ChatListItem.Message>().map { it.message.msgId.removePrefix("h").toInt() }
        assertThat(loaded.last()).isEqualTo(0)
        assertThat(loaded).isInStrictOrder(Comparator.reverseOrder<Int>())
        assertThat(loaded.zipWithNext { a, b -> a - b }.all { it == 1 }).isTrue()
        assertThat(items.last()).isInstanceOf(ChatListItem.TimeHeader::class.java)
    }

    /** 5.8 发送名片：内容为同事的 JID 与姓名，降级文本"[名片] 姓名"；列表中以本地组织架构为准显示 */
    @Test
    fun sendCard_andCardPeer() = runTest(mainDispatcherRule.testDispatcher) {
        db.employeeDao().upsert(listOf(EmployeeEntity(CAROL, "10003", "王五", "wangwu", "ww", "d", "经理", "", "", "", 0, "h3")))
        val (vm, _) = newVm()

        vm.sendCard(CAROL)
        runCurrent()

        val saved = db.messageDao().last(BOB)!!
        assertThat(saved.type).isEqualTo("card")
        assertThat(saved.body).isEqualTo("[名片] 王五")
        val item = vm.items.asSnapshot().first() as ChatListItem.Message
        assertThat(item.card).isEqualTo(CardPeer(CAROL, "王五", "经理", "h3"))

        // 组织架构中改名后显示新名字（以本地组织架构为准）
        db.employeeDao().upsert(listOf(EmployeeEntity(CAROL, "10003", "王小五", "wangxiaowu", "wxw", "d", "", "", "", "", 0, "")))
        val updated = vm.items.asSnapshot().first() as ChatListItem.Message
        assertThat(updated.card).isEqualTo(CardPeer(CAROL, "王小五", "10003", ""))
    }

    /** 组织架构中没有该同事：用名片中的姓名，副标题为工号 */
    @Test
    fun cardPeer_unknownEmployee_fallback() = runTest(mainDispatcherRule.testDispatcher) {
        val content = MessageContent.Card("20001@im.example.com", "外单位")
        db.messageDao().insert(
            MessageEntity(
                msgId = "c", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "card",
                body = "[名片] 外单位", contentJson = MessageJson.encodeContent(content),
                status = MessageEntity.STATUS_RECEIVED, serverTime = 1,
            ),
        )
        val (vm, _) = newVm()
        val item = vm.items.asSnapshot().first() as ChatListItem.Message
        assertThat(item.card).isEqualTo(CardPeer("20001@im.example.com", "外单位", "20001", ""))
    }

    private companion object {
        const val BOB = "10002@im.example.com"
        const val CAROL = "10003@im.example.com"
    }
}
