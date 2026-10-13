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
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.msg.Mention
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SendQueue] 与 [MessageRepository.sendText] 测试：Room 内存库（查询运行在测试调度器上，虚拟时间可控）+ [FakeXmppClient]。
 *
 * 被测协程运行在 backgroundScope 中，推进时用 runCurrent / advanceTimeBy（见 SessionManagerTest 的说明）。
 */
@RunWith(RobolectricTestRunner::class)
class SendQueueTest {

    private val client = FakeXmppClient()

    private var holder: UserDatabaseHolder? = null

    /** 顺序生成的消息 ID：m1、m2…… */
    private val ids = object : IdGenerator {
        private var next = 0
        override fun messageId() = "m${++next}"
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
     * @property queue 发送队列
     * @property repository 消息仓库
     * @property db 数据库
     */
    private class Rig(val queue: SendQueue, val repository: MessageRepository, val db: ImDatabase)

    /**
     * 创建测试环境：打开 10001 的内存库，时钟使用虚拟时间。
     *
     * @param login 是否先登录（连接状态为已认证）
     * @param beforeQueue 创建队列前对数据库的操作（模拟上次运行留下的数据）
     * @return 测试环境
     */
    private suspend fun TestScope.rig(login: Boolean = true, beforeQueue: suspend (ImDatabase) -> Unit = {}): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        val clock = object : Clock {
            override fun nowMillis() = 1_000_000 + testScheduler.currentTime
            override fun elapsedMillis() = testScheduler.currentTime
        }
        beforeQueue(db)
        if (login) client.login(Credential("10001", "x"))
        val queue = SendQueue(client, newHolder, backgroundScope, clock)
        val repository = MessageRepository(newHolder, queue, ServerClock(clock), ids) { "10001@im.example.com" }
        runCurrent()
        return Rig(queue, repository, db)
    }

    /** 读取消息的当前状态 */
    private suspend fun Rig.status(msgId: String, conversationId: String = BOB): MessageEntity =
        db.messageDao().get(conversationId, msgId)!!

    @Test
    fun sendText_connected_sentAndAcked() = runTest {
        val rig = rig()

        val message = rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "你好")
        assertThat(message.status).isEqualTo(MessageEntity.STATUS_SENDING)
        assertThat(message.senderJid).isEqualTo("10001@im.example.com")
        assertThat(message.serverTime).isEqualTo(1_000_000)
        runCurrent()

        val stanza = MessageCodec.parse(client.sentMessages.single()).message
        assertThat(stanza.id).isEqualTo("m1")
        assertThat(stanza.to).isEqualTo(BOB)
        assertThat(stanza.type).isEqualTo("chat")
        assertThat(stanza.body).isEqualTo("你好")
        assertThat(stanza.requestReceipt).isTrue()
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
        // 会话：新建并更新最后一条
        val conversation = rig.db.conversationDao().get(BOB)!!
        assertThat(conversation.type).isEqualTo(ConversationEntity.TYPE_SINGLE)
        assertThat(conversation.lastMsgRowId).isEqualTo(message.rowId)
        assertThat(conversation.lastMsgPreview).isEqualTo("你好")
        assertThat(conversation.unreadCount).isEqualTo(0)
    }

    /** 未连接时排队，连上后按写入顺序发送 */
    @Test
    fun queuedWhileDisconnected_sentInOrderAfterLogin() = runTest {
        val rig = rig(login = false)

        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "1")
        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "2")
        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "3")
        advanceTimeBy(10_000)
        assertThat(client.sentMessages).isEmpty()
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_SENDING)

        client.login(Credential("10001", "x"))
        runCurrent()

        assertThat(client.sentMessages.map { MessageCodec.parse(it).message.body }).containsExactly("1", "2", "3").inOrder()
        assertThat(rig.status("m3").status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    /** 30 秒未连上：标记失败；每条消息单独计时 */
    @Test
    fun notConnectedFor30s_failsEachOnItsOwnDeadline() = runTest {
        val rig = rig(login = false)

        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "早")
        advanceTimeBy(20_000)
        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "晚")
        advanceTimeBy(10_001)

        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_FAILED)
        assertThat(rig.status("m1").errorCode).isEqualTo(SendError.NOT_CONNECTED)
        assertThat(rig.status("m2").status).isEqualTo(MessageEntity.STATUS_SENDING)

        advanceTimeBy(20_000)
        assertThat(rig.status("m2").status).isEqualTo(MessageEntity.STATUS_FAILED)
        assertThat(client.sentMessages).isEmpty()
    }

    /** 30 秒内未收到服务器确认：失败；不影响后面的消息 */
    @Test
    fun ackTimeout_marksFailedAndContinues() = runTest {
        val rig = rig()
        client.messageAckHandler = { xml, timeout ->
            if (MessageCodec.parse(xml).message.body == "丢") {
                delay(timeout)
                throw XmppException.Timeout()
            }
            true
        }

        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "丢")
        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "好")
        advanceTimeBy(29_999)
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_SENDING)

        advanceTimeBy(2)
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_FAILED)
        assertThat(rig.status("m1").errorCode).isEqualTo(SendError.TIMEOUT)
        assertThat(rig.status("m2").status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    /** 失败后手动重发：沿用原消息 ID */
    @Test
    fun resend_keepsMessageId() = runTest {
        val rig = rig()
        client.messageAckHandler = { _, _ -> throw XmppException.Timeout() }
        val message = rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "重发我")
        runCurrent()
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_FAILED)

        client.messageAckHandler = { _, _ -> true }
        assertThat(rig.repository.resend(message.rowId)).isTrue()
        runCurrent()

        assertThat(client.sentMessages.map { MessageCodec.parse(it).message.id }).containsExactly("m1", "m1")
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
        assertThat(rig.status("m1").errorCode).isNull()
        // 不是失败状态的消息不能重发
        assertThat(rig.repository.resend(message.rowId)).isFalse()
    }

    /** 应用被杀后重新登录：数据库中"发送中"的消息自动恢复发送 */
    @Test
    fun pendingFromLastRun_resumedAfterLogin() = runTest {
        // 模拟重启：数据库中留有上次的"发送中"消息，队列创建后、登录前不发送
        val rig = rig(login = false) { db ->
            db.messageDao().insert(
                MessageEntity(
                    msgId = "old",
                    conversationId = BOB,
                    senderJid = "10001@im.example.com",
                    direction = MessageEntity.DIRECTION_OUT,
                    type = "text",
                    body = "上次没发完",
                    status = MessageEntity.STATUS_SENDING,
                    serverTime = 1,
                ),
            )
        }
        assertThat(client.sentMessages).isEmpty()

        client.login(Credential("10001", "x"))
        runCurrent()

        assertThat(MessageCodec.parse(client.sentMessages.single()).message.id).isEqualTo("old")
        assertThat(rig.status("old").status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    /** 发送时发现连接已断开（状态尚未更新）：消息保持"发送中"，重连后再发 */
    @Test
    fun notConnectedDuringSend_staysPendingAndRetries() = runTest {
        val rig = rig()
        var first = true
        client.messageAckHandler = { _, _ ->
            if (first) {
                first = false
                throw XmppException.NotConnected()
            }
            true
        }

        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "x")
        runCurrent()
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_SENDING)

        advanceTimeBy(1_001)
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
        assertThat(client.sentMessages).hasSize(2)
    }

    /** 服务器没有启用流管理：无法确认，写出即视为已发送 */
    @Test
    fun noStreamManagement_treatedAsSent() = runTest {
        val rig = rig()
        client.messageAckHandler = { _, _ -> false }

        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "x")
        runCurrent()

        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    /** 群聊：type='groupchat'、不请求回执、带 @ */
    @Test
    fun groupConversation_groupchatWithMentions() = runTest {
        val rig = rig()
        val mentions = listOf(Mention(0, 3, "10002@im.example.com"))

        rig.repository.sendText(GROUP, ConversationEntity.TYPE_GROUP, "@李四 你好", mentions = mentions)
        runCurrent()

        val stanza = MessageCodec.parse(client.sentMessages.single()).message
        assertThat(stanza.type).isEqualTo("groupchat")
        assertThat(stanza.requestReceipt).isFalse()
        assertThat(stanza.mentions).isEqualTo(mentions)
        assertThat(rig.status("m1", GROUP).mentionsJson).isNotNull()
    }

    /** 引用回复：原消息在本地时带上原发送方；不在本地时不带引用 */
    @Test
    fun reply_resolvesOriginalSender() = runTest {
        val rig = rig()
        rig.db.messageDao().insert(
            MessageEntity(
                msgId = "orig",
                conversationId = BOB,
                senderJid = BOB,
                direction = MessageEntity.DIRECTION_IN,
                type = "text",
                body = "原消息",
                status = MessageEntity.STATUS_RECEIVED,
                serverTime = 1,
            ),
        )

        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "好的", replyToMsgId = "orig")
        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "嗯", replyToMsgId = "missing")
        runCurrent()

        val stanzas = client.sentMessages.map { MessageCodec.parse(it).message }
        assertThat(stanzas[0].reply?.to).isEqualTo(BOB)
        assertThat(stanzas[0].reply?.id).isEqualTo("orig")
        assertThat(stanzas[1].reply).isNull()
    }

    /** 退出登录（数据库关闭）后队列停止，不再发送 */
    @Test
    fun databaseClosed_queueStops() = runTest {
        val rig = rig(login = false)
        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "x")
        holder!!.close()
        runCurrent()

        client.login(Credential("10001", "x"))
        advanceTimeBy(60_000)

        assertThat(client.sentMessages).isEmpty()
    }

    /** 连接断开期间发送：等到重连 */
    @Test
    fun disconnectedThenReconnected() = runTest {
        val rig = rig()
        client.simulateDisconnect(DisconnectReason.Network(null))

        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "x")
        advanceTimeBy(5_000)
        assertThat(client.sentMessages).isEmpty()

        client.reconnect()
        runCurrent()
        assertThat(rig.status("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    @Test
    fun sendText_validation() = runTest {
        val rig = rig()

        assertThat(runCatching { rig.repository.sendText(BOB, 0, "  \n") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        // 上限按码点计：5000 个 emoji（10000 个 char）可以发送，5001 个不行
        val emoji = "😀"
        rig.repository.sendText(BOB, 0, emoji.repeat(MessageRepository.MAX_TEXT_LENGTH))
        assertThat(runCatching { rig.repository.sendText(BOB, 0, emoji.repeat(MessageRepository.MAX_TEXT_LENGTH + 1)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val BOB = "10002@im.example.com"
        const val GROUP = "g1@conference.im.example.com"
    }
}
