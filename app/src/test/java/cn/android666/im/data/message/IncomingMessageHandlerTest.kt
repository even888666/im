package cn.android666.im.data.message

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [IncomingMessageHandler] 测试：Room 内存库 + [FakeXmppClient] 模拟服务器推送报文。
 */
@RunWith(RobolectricTestRunner::class)
class IncomingMessageHandlerTest {

    private val client = FakeXmppClient()

    private var holder: UserDatabaseHolder? = null

    /** 顺序生成的 ID：r1、r2…… */
    private val ids = object : IdGenerator {
        private var next = 0
        override fun messageId() = "r${++next}"
        override fun roomId() = "g1"
        override fun callId() = "c1"
        override fun uuid() = "u1"
    }

    @After
    fun tearDown() {
        holder?.close()
    }

    /**
     * 测试环境。
     *
     * @property handler 被测对象
     * @property repository 消息仓库
     * @property db 数据库
     * @property readMarkers 已读标记
     */
    private class Rig(
        val handler: IncomingMessageHandler,
        val repository: MessageRepository,
        val db: ImDatabase,
        val readMarkers: ReadMarkers,
    )

    /**
     * 创建测试环境：10001 已登录，服务器时间 = 虚拟时间 + 1_000_000。
     *
     * @param loggedIn 是否已打开用户数据库（已登录）
     * @return 测试环境
     */
    private suspend fun TestScope.rig(loggedIn: Boolean = true): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        if (!loggedIn) newHolder.close()
        val clock = object : Clock {
            override fun nowMillis() = 1_000_000 + testScheduler.currentTime
            override fun elapsedMillis() = testScheduler.currentTime
        }
        client.login(Credential("10001", "x"))
        val selfJid = { newHolder.username?.let { "$it@im.example.com" } }
        val queue = SendQueue(client, newHolder, backgroundScope, clock)
        val repository = MessageRepository(newHolder, queue, ServerClock(clock), ids, selfJid)
        val readMarkers = ReadMarkers(client, repository, ConversationRepository(newHolder), ids)
        val handler = IncomingMessageHandler(client, repository, readMarkers, ServerClock(clock), ids, selfJid, backgroundScope)
        runCurrent()
        return Rig(handler, repository, db, readMarkers)
    }

    /** 对方发来的文本消息（协议规范 §6.1） */
    private fun chat(id: String?, body: String = "你好", extra: String = "", from: String = "$BOB/pc") =
        "<message xmlns='jabber:client' type='chat'${id?.let { " id='$it'" } ?: ""} from='$from' to='10001@im.example.com/android'>" +
            "<body>$body</body>${id?.let { "<origin-id xmlns='urn:xmpp:sid:0' id='$it'/>" } ?: ""}" +
            "<request xmlns='urn:xmpp:receipts'/><markable xmlns='urn:xmpp:chat-markers:0'/>$extra</message>"

    @Test
    fun chat_savedWithConversationAndReceipt() = runTest {
        val rig = rig()
        val notified = async(start = CoroutineStart.UNDISPATCHED) { rig.handler.newMessages.first() }

        client.receive(chat("5b1e", extra = "<stanza-id xmlns='urn:xmpp:sid:0' id='s-9' by='10001@im.example.com'/>"))
        runCurrent()

        val saved = rig.db.messageDao().get(BOB, "5b1e")!!
        assertThat(saved.direction).isEqualTo(MessageEntity.DIRECTION_IN)
        assertThat(saved.status).isEqualTo(MessageEntity.STATUS_RECEIVED)
        assertThat(saved.senderJid).isEqualTo(BOB)
        assertThat(saved.body).isEqualTo("你好")
        assertThat(saved.type).isEqualTo("text")
        assertThat(saved.stanzaId).isEqualTo("s-9")
        // 实时消息：本地接收时间换算的服务器时间
        assertThat(saved.serverTime).isEqualTo(1_000_000)
        assertThat(notified.await().msgId).isEqualTo("5b1e")

        val conversation = rig.db.conversationDao().get(BOB)!!
        assertThat(conversation.type).isEqualTo(ConversationEntity.TYPE_SINGLE)
        assertThat(conversation.unreadCount).isEqualTo(1)
        assertThat(conversation.lastMsgPreview).isEqualTo("你好")

        // 写库后回执：发给原消息的完整 JID
        val receipt = MessageCodec.parse(client.sentMessages.single()).message
        assertThat(receipt.to).isEqualTo("$BOB/pc")
        assertThat(receipt.receivedId).isEqualTo("5b1e")
    }

    /** 重复的消息（离线消息、重发）丢弃，未读不增加；仍然回执 */
    @Test
    fun duplicate_droppedButReceiptedAgain() = runTest {
        val rig = rig()

        client.receive(chat("5b1e"))
        client.receive(chat("5b1e", body = "重发"))
        runCurrent()

        assertThat(rig.db.messageDao().get(BOB, "5b1e")!!.body).isEqualTo("你好")
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(1)
        assertThat(client.sentMessages).hasSize(2)
    }

    /** 离线消息：使用 delay 时间；比会话最后一条早时不改变预览，但计入未读 */
    @Test
    fun offline_usesDelayStampAndKeepsLatestPreview() = runTest {
        val rig = rig()

        client.receive(chat("new", body = "新的"))
        client.receive(chat("old", body = "旧的", extra = "<delay xmlns='urn:xmpp:delay' stamp='1970-01-01T00:00:01.000Z'/>"))
        runCurrent()

        assertThat(rig.db.messageDao().get(BOB, "old")!!.serverTime).isEqualTo(1_000)
        val conversation = rig.db.conversationDao().get(BOB)!!
        assertThat(conversation.lastMsgPreview).isEqualTo("新的")
        assertThat(conversation.unreadCount).isEqualTo(2)
    }

    /** 正在查看的会话：不计未读 */
    @Test
    fun activeConversation_noUnread() = runTest {
        val rig = rig()
        rig.repository.setActiveConversation(BOB)

        client.receive(chat("a"))
        runCurrent()

        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(0)
        assertThat(rig.repository.activeConversationId()).isEqualTo(BOB)
    }

    /** 非文本内容保存为 JSON；不认识的类型没有 body 时显示升级提示 */
    @Test
    fun contentTypes() = runTest {
        val rig = rig()

        client.receive(
            chat(
                "card",
                body = "[名片] 王五",
                extra = "<content xmlns='urn:android666:im:msg:1' type='card'><card jid='10003@im.example.com' name='王五'/></content>",
            ),
        )
        client.receive(
            "<message type='chat' id='future' from='$BOB/pc'><body></body>" +
                "<content xmlns='urn:android666:im:msg:1' type='vote'/></message>",
        )
        runCurrent()

        val card = rig.db.messageDao().get(BOB, "card")!!
        assertThat(card.type).isEqualTo("card")
        assertThat(MessageJson.decodeContent(card.type, card.contentJson)).isEqualTo(MessageContent.Card("10003@im.example.com", "王五"))
        val future = rig.db.messageDao().get(BOB, "future")!!
        assertThat(future.body).isEqualTo(IncomingMessageMapper.UNSUPPORTED_TEXT)
        assertThat(MessageJson.decodeContent(future.type, future.contentJson)).isEqualTo(MessageContent.Unknown("vote"))
        // 只回执请求了回执的消息（第二条没有 request）
        assertThat(client.sentMessages.map { MessageCodec.parse(it).message.receivedId }).containsExactly("card")
    }

    /** 引用回复：记录原消息 ID */
    @Test
    fun reply_recorded() = runTest {
        val rig = rig()

        client.receive(chat("r", extra = "<reply xmlns='urn:xmpp:reply:0' to='10001@im.example.com' id='orig'/>"))
        runCurrent()

        assertThat(rig.db.messageDao().get(BOB, "r")!!.replyToMsgId).isEqualTo("orig")
    }

    /** 没有消息 ID：生成一个，仍然保存（无法去重）；不回执（没有 ID 可回） */
    @Test
    fun missingId_generated() = runTest {
        val rig = rig()

        client.receive(chat(null))
        runCurrent()

        assertThat(rig.db.messageDao().last(BOB)!!.msgId).isEqualTo("r1")
        assertThat(client.sentMessages).isEmpty()
    }

    /** 无法解析、未登录、暂不处理的类型：不崩溃，后续消息照常处理 */
    @Test
    fun ignoredAndBroken_doNotStopProcessing() = runTest {
        val rig = rig()

        client.receive("<message")
        client.receive("<iq type='get'/>")
        // 群消息（M6）、错误（4.5）、Carbons（4.6）、MAM（4.10）、回执（4.5）、没有发送方的消息
        client.receive("<message type='groupchat' id='g' from='g1@conference.im.example.com/10002'><body>x</body></message>")
        client.receive("<message type='error' id='e' from='$BOB'><error type='cancel'/></message>")
        client.receive(
            "<message from='10001@im.example.com'><sent xmlns='urn:xmpp:carbons:2'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message xmlns='jabber:client' type='chat' id='c' from='10001@im.example.com/pc' to='$BOB'><body>x</body></message>" +
                "</forwarded></sent></message>",
        )
        client.receive(
            "<message><result xmlns='urn:xmpp:mam:2' queryid='q' id='s'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message xmlns='jabber:client' type='chat' id='h' from='$BOB/pc'><body>x</body></message></forwarded></result></message>",
        )
        client.receive("<message id='rc' from='$BOB/pc'><received xmlns='urn:xmpp:receipts' id='m1'/></message>")
        client.receive("<message type='chat' id='nofrom'><body>x</body></message>")
        client.receive(chat("ok"))
        runCurrent()

        assertThat(rig.db.messageDao().get(BOB, "ok")).isNotNull()
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(1)
        assertThat(rig.db.conversationDao().get("g1@conference.im.example.com")).isNull()
    }

    /** 未登录（数据库未打开）时收到：忽略，不崩溃 */
    @Test
    fun notLoggedIn_ignored() = runTest {
        rig(loggedIn = false)

        client.receive(chat("a"))
        runCurrent()

        assertThat(client.sentMessages).isEmpty()
    }

    /** 插入一条发出的消息 */
    private suspend fun Rig.outgoing(msgId: String, status: Int, conversationId: String = BOB): Long = db.messageDao().insert(
        MessageEntity(
            msgId = msgId,
            conversationId = conversationId,
            senderJid = "10001@im.example.com",
            direction = MessageEntity.DIRECTION_OUT,
            type = "text",
            body = "x",
            status = status,
            errorCode = if (status == MessageEntity.STATUS_FAILED) SendError.TIMEOUT else null,
            serverTime = 1,
        ),
    )

    /** 送达回执（4.5）：已发送 / 超时失败 → 已送达；已读的不回退；其他会话的同 ID 不受影响 */
    @Test
    fun receipt_marksDelivered() = runTest {
        val rig = rig()
        rig.outgoing("sent", MessageEntity.STATUS_SENT)
        rig.outgoing("timedOut", MessageEntity.STATUS_FAILED)
        rig.outgoing("read", MessageEntity.STATUS_READ)
        rig.outgoing("sent", MessageEntity.STATUS_SENT, conversationId = "10003@im.example.com")

        for (id in listOf("sent", "timedOut", "read")) {
            client.receive("<message id='rc-$id' from='$BOB/pc'><received xmlns='urn:xmpp:receipts' id='$id'/></message>")
        }
        runCurrent()

        assertThat(rig.db.messageDao().get(BOB, "sent")!!.status).isEqualTo(MessageEntity.STATUS_DELIVERED)
        val timedOut = rig.db.messageDao().get(BOB, "timedOut")!!
        assertThat(timedOut.status).isEqualTo(MessageEntity.STATUS_DELIVERED)
        assertThat(timedOut.errorCode).isNull()
        assertThat(rig.db.messageDao().get(BOB, "read")!!.status).isEqualTo(MessageEntity.STATUS_READ)
        assertThat(rig.db.messageDao().get("10003@im.example.com", "sent")!!.status).isEqualTo(MessageEntity.STATUS_SENT)
        // 回执本身不是消息：不入库、不计未读
        assertThat(rig.db.conversationDao().get(BOB)).isNull()
    }

    /** 服务器退回（4.5，协议规范 §6.4）：敏感词 → 失败并记录自定义错误码；没有自定义码时记录标准条件 */
    @Test
    fun error_marksRejected() = runTest {
        val rig = rig()
        rig.outgoing("bad", MessageEntity.STATUS_SENT)
        rig.outgoing("nobody", MessageEntity.STATUS_SENDING, conversationId = "99999@im.example.com")
        rig.outgoing("delivered", MessageEntity.STATUS_DELIVERED)

        client.receive(
            "<message xmlns='jabber:client' type='error' id='bad' from='$BOB'><error type='modify'>" +
                "<policy-violation xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                "<app-error xmlns='urn:android666:im:error:1' code='sensitive-word'/></error></message>",
        )
        client.receive(
            "<message type='error' id='nobody' from='99999@im.example.com'><error type='cancel'>" +
                "<service-unavailable xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></message>",
        )
        client.receive(
            "<message type='error' id='delivered' from='$BOB'><error type='cancel'>" +
                "<service-unavailable xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></message>",
        )
        // 回执被退回：找不到对应消息，忽略
        client.receive("<message type='error' id='r-1' from='$BOB/pc'><error type='cancel'/></message>")
        runCurrent()

        val bad = rig.db.messageDao().get(BOB, "bad")!!
        assertThat(bad.status).isEqualTo(MessageEntity.STATUS_FAILED)
        assertThat(bad.errorCode).isEqualTo("sensitive-word")
        assertThat(rig.db.messageDao().get("99999@im.example.com", "nobody")!!.errorCode).isEqualTo("service-unavailable")
        assertThat(rig.db.messageDao().get(BOB, "delivered")!!.status).isEqualTo(MessageEntity.STATUS_DELIVERED)
    }

    /**
     * Carbons 包装。
     *
     * @param kind sent / received
     * @param inner 内层 message
     * @param outerFrom 外层 from（正常为本人 bare JID）
     */
    private fun carbon(kind: String, inner: String, outerFrom: String = SELF) =
        "<message xmlns='jabber:client' from='$outerFrom' to='$SELF/android' type='chat'>" +
            "<$kind xmlns='urn:xmpp:carbons:2'><forwarded xmlns='urn:xmpp:forward:0'>$inner</forwarded></$kind></message>"

    /** 4.6：本人 PC 端发给李四的消息抄送过来 → 保存为本人发出、已发送，不计未读 */
    @Test
    fun carbonSent_savedAsOwnMessage() = runTest {
        val rig = rig()

        client.receive(
            carbon(
                "sent",
                "<message xmlns='jabber:client' type='chat' id='pc1' from='$SELF/pc' to='$BOB'>" +
                    "<body>PC 端发的</body><request xmlns='urn:xmpp:receipts'/></message>",
            ),
        )
        runCurrent()

        val saved = rig.db.messageDao().get(BOB, "pc1")!!
        assertThat(saved.direction).isEqualTo(MessageEntity.DIRECTION_OUT)
        assertThat(saved.status).isEqualTo(MessageEntity.STATUS_SENT)
        assertThat(saved.senderJid).isEqualTo(SELF)
        val conversation = rig.db.conversationDao().get(BOB)!!
        assertThat(conversation.unreadCount).isEqualTo(0)
        assertThat(conversation.lastMsgPreview).isEqualTo("PC 端发的")
        assertThat(client.sentMessages).isEmpty()
    }

    /** 4.6：本人 PC 端收到李四的消息抄送过来 → 保存为收到的、计未读，但不由本端回执 */
    @Test
    fun carbonReceived_savedWithoutReceipt() = runTest {
        val rig = rig()

        client.receive(carbon("received", chat("in1", from = "$BOB/pc").replace("to='10001@im.example.com/android'", "to='$SELF/pc'")))
        runCurrent()

        val saved = rig.db.messageDao().get(BOB, "in1")!!
        assertThat(saved.direction).isEqualTo(MessageEntity.DIRECTION_IN)
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(1)
        assertThat(client.sentMessages).isEmpty()

        // 之后本端又直接收到同一条（例如离线消息）：去重，但会回执
        client.receive(chat("in1"))
        runCurrent()
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(1)
        assertThat(client.sentMessages).hasSize(1)
    }

    /** 4.6：其他端收到的送达回执抄送过来：更新为已送达 */
    @Test
    fun carbonReceivedReceipt_marksDelivered() = runTest {
        val rig = rig()
        rig.outgoing("m1", MessageEntity.STATUS_SENT)

        client.receive(carbon("received", "<message xmlns='jabber:client' id='rc' from='$BOB/pc' to='$SELF/pc'><received xmlns='urn:xmpp:receipts' id='m1'/></message>"))
        runCurrent()

        assertThat(rig.db.messageDao().get(BOB, "m1")!!.status).isEqualTo(MessageEntity.STATUS_DELIVERED)
    }

    /** 外层 from 不是本人：伪造的抄送，丢弃 */
    @Test
    fun spoofedCarbon_ignored() = runTest {
        val rig = rig()

        client.receive(
            carbon(
                "sent",
                "<message xmlns='jabber:client' type='chat' id='fake' from='$SELF/pc' to='$BOB'><body>伪造</body></message>",
                outerFrom = "$BOB/pc",
            ),
        )
        runCurrent()

        assertThat(rig.db.messageDao().get(BOB, "fake")).isNull()
    }

    /** 文件传输助手：其他端发给本人 bare JID 的消息（直接收到或抄送），会话类型为 TYPE_SELF，按消息 ID 去重 */
    @Test
    fun fileTransferAssistant() = runTest {
        val rig = rig()
        val inner = "<message xmlns='jabber:client' type='chat' id='f1' from='$SELF/pc' to='$SELF'><body>传个文件</body>" +
            "<request xmlns='urn:xmpp:receipts'/></message>"

        client.receive(inner)
        client.receive(carbon("sent", inner))
        runCurrent()

        val saved = rig.db.messageDao().get(SELF, "f1")!!
        assertThat(saved.direction).isEqualTo(MessageEntity.DIRECTION_OUT)
        val conversation = rig.db.conversationDao().get(SELF)!!
        assertThat(conversation.type).isEqualTo(ConversationEntity.TYPE_SELF)
        assertThat(conversation.unreadCount).isEqualTo(0)
        // 不给自己回执
        assertThat(client.sentMessages).isEmpty()
    }

    /** 本端发给文件传输助手的消息：发送后服务器路由回本端时按 ID 去重 */
    @Test
    fun ownMessageToSelf_echoDeduplicated() = runTest {
        val rig = rig()

        val sent = rig.repository.sendText(SELF, ConversationEntity.TYPE_SELF, "备忘")
        runCurrent()
        // 服务器把发给本人 bare JID 的消息也路由回本端
        client.receive(client.sentMessages.single().replace("<message ", "<message from='$SELF/android' "))
        runCurrent()

        assertThat(rig.db.messageDao().last(SELF)!!.rowId).isEqualTo(sent.rowId)
        assertThat(rig.db.messageDao().get(SELF, sent.msgId)!!.status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    /** 已发送的已读报文中被标记的消息 ID */
    private fun displayedSent(): List<String?> =
        client.sentMessages.map { MessageCodec.parse(it).message }.filter { it.displayedId != null }.map { it.displayedId }

    /** 4.9：打开会话 → 清除未读，对最新一条对方的消息发送已读；同一条不重复发送；有新消息后再发 */
    @Test
    fun markConversationRead_sendsDisplayedOnce() = runTest {
        val rig = rig()
        client.receive(chat("a"))
        client.receive(chat("b"))
        runCurrent()
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(2)

        rig.readMarkers.markConversationRead(BOB)
        rig.readMarkers.markConversationRead(BOB)

        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(0)
        assertThat(displayedSent()).containsExactly("b")
        val displayed = MessageCodec.parse(client.sentMessages.last()).message
        assertThat(displayed.to).isEqualTo(BOB)

        client.receive(chat("c"))
        runCurrent()
        rig.readMarkers.markConversationRead(BOB)
        assertThat(displayedSent()).containsExactly("b", "c").inOrder()
    }

    /** 未连接时已读标记发送失败：不记录，下次再发 */
    @Test
    fun markConversationRead_retryAfterFailure() = runTest {
        val rig = rig()
        client.receive(chat("a"))
        runCurrent()
        client.messageAckHandler = { _, _ -> throw cn.android666.im.core.xmpp.XmppException.Timeout() }

        rig.readMarkers.markConversationRead(BOB)
        client.messageAckHandler = { _, _ -> true }
        rig.readMarkers.markConversationRead(BOB)

        assertThat(displayedSent()).containsExactly("a", "a")
    }

    /** 没有对方的消息：只清未读，不发送 */
    @Test
    fun markConversationRead_noIncoming() = runTest {
        val rig = rig()
        rig.readMarkers.markConversationRead(BOB)
        assertThat(client.sentMessages).isEmpty()
    }

    /** 正在查看的会话收到新消息：自动发送已读 */
    @Test
    fun activeConversation_newMessageMarkedRead() = runTest {
        val rig = rig()
        rig.repository.setActiveConversation(BOB)

        client.receive(chat("live"))
        runCurrent()

        assertThat(displayedSent()).containsExactly("live")
    }

    /** 对方已读（直接收到或其他端收到的抄送）：该消息及之前本人发出的改为已读，之后的不变 */
    @Test
    fun peerDisplayed_marksReadUpTo() = runTest {
        val rig = rig()
        rig.db.messageDao().insert(outgoingAt("o1", 100))
        rig.db.messageDao().insert(outgoingAt("o2", 200))
        rig.db.messageDao().insert(outgoingAt("o3", 300))

        client.receive("<message type='chat' id='d1' from='$BOB/pc'><displayed xmlns='urn:xmpp:chat-markers:0' id='o2'/></message>")
        runCurrent()
        assertThat(rig.db.messageDao().get(BOB, "o1")!!.status).isEqualTo(MessageEntity.STATUS_READ)
        assertThat(rig.db.messageDao().get(BOB, "o2")!!.status).isEqualTo(MessageEntity.STATUS_READ)
        assertThat(rig.db.messageDao().get(BOB, "o3")!!.status).isEqualTo(MessageEntity.STATUS_SENT)

        client.receive(
            carbon(
                "received",
                "<message xmlns='jabber:client' type='chat' id='d2' from='$BOB/pc' to='$SELF/pc'>" +
                    "<displayed xmlns='urn:xmpp:chat-markers:0' id='o3'/></message>",
            ),
        )
        runCurrent()
        assertThat(rig.db.messageDao().get(BOB, "o3")!!.status).isEqualTo(MessageEntity.STATUS_READ)
        // 已读报文本身不入库
        assertThat(rig.db.conversationDao().get(BOB)).isNull()
    }

    /** 本人其他端已读（Carbons sent 的 displayed）：未读改为之后的消息数；本端不再重复发送该已读 */
    @Test
    fun displayedFromOtherDevice_syncsUnread() = runTest {
        val rig = rig()
        client.receive(chat("a", extra = "<delay xmlns='urn:xmpp:delay' stamp='1970-01-01T00:00:01.000Z'/>"))
        client.receive(chat("b", extra = "<delay xmlns='urn:xmpp:delay' stamp='1970-01-01T00:00:02.000Z'/>"))
        client.receive(chat("c", extra = "<delay xmlns='urn:xmpp:delay' stamp='1970-01-01T00:00:03.000Z'/>"))
        runCurrent()
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(3)

        client.receive(
            carbon(
                "sent",
                "<message xmlns='jabber:client' type='chat' id='rd' from='$SELF/pc' to='$BOB'>" +
                    "<displayed xmlns='urn:xmpp:chat-markers:0' id='b'/><store xmlns='urn:xmpp:hints'/></message>",
            ),
        )
        runCurrent()
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(1)

        // 其他端已读到最后一条：清零；本端再打开会话不重复发送
        client.receive(
            carbon(
                "sent",
                "<message xmlns='jabber:client' type='chat' id='rd2' from='$SELF/pc' to='$BOB'>" +
                    "<displayed xmlns='urn:xmpp:chat-markers:0' id='c'/></message>",
            ),
        )
        runCurrent()
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(0)
        val before = client.sentMessages.size
        rig.readMarkers.markConversationRead(BOB)
        assertThat(client.sentMessages.size).isEqualTo(before)
    }

    /** 发出的消息（指定时间） */
    private fun outgoingAt(msgId: String, time: Long) = MessageEntity(
        msgId = msgId, conversationId = BOB, senderJid = SELF, direction = MessageEntity.DIRECTION_OUT, type = "text",
        body = "x", status = MessageEntity.STATUS_SENT, serverTime = time,
    )

    private companion object {
        const val SELF = "10001@im.example.com"
        const val BOB = "10002@im.example.com"
    }
}
