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
import cn.android666.im.data.message.MessageRepository
import cn.android666.im.data.message.MessageState
import cn.android666.im.data.message.ReadMarkers
import cn.android666.im.data.message.SendQueue
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeXmppClient
import cn.android666.im.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
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
        val vm = ChatViewModel(id, kind, messages, conversations, readMarkers, OrgRepository(client, holder, clock))
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

    private companion object {
        const val BOB = "10002@im.example.com"
    }
}
