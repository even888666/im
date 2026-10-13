package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.extension.msg.IncomingMessage
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.extension.msg.MessageEnvelope
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * 消息收发集成测试（开发计划 4.3）：真实 Openfire 上两个账号互发，验证流管理确认、接收流、送达回执，
 * 以及 MessageCodec 生成的报文经服务器转发后内容不丢失。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*MessagingIT*'`
 *
 * 使用测试账号 10005、10006。
 */
class MessagingIT {

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    private val clients = mutableListOf<SmackXmppClient>()

    private val jidA = "${ItServer.user(5)}@${ItServer.domain}"
    private val jidB = "${ItServer.user(6)}@${ItServer.domain}"

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { runCatching { it.logout() } }
    }

    /**
     * 登录并发送初始 presence。
     *
     * @param index 测试账号序号
     * @param resource 登录的 resource（同一账号多端同时在线时区分）
     * @return 客户端
     */
    private suspend fun login(index: Int, resource: String = ServerConfig.RESOURCE_ANDROID): SmackXmppClient = SmackXmppClient(
        serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT, resource = resource),
        trustManager = ItServer.trustManager,
        dispatchers = dispatchers,
        clock = SystemTimeClock,
        logStanzas = { false },
    ).also {
        clients += it
        it.login(Credential(ItServer.user(index), ItServer.testUserPassword))
        it.sendPresence()
    }

    /**
     * 开始等待第一条满足条件的消息（先订阅再触发，避免错过）。
     *
     * @param client 接收方
     * @param predicate 条件
     * @return 等待结果
     */
    private fun kotlinx.coroutines.CoroutineScope.expect(
        client: SmackXmppClient,
        predicate: (IncomingMessage) -> Boolean,
    ): Deferred<IncomingMessage> = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(TIMEOUT) { client.incomingMessages.map { MessageCodec.parse(it) }.first(predicate) }
    }

    @Test
    fun chat_ackedReceivedAndReceipted(): Unit = runBlocking {
        val a = login(5)
        val b = login(6)
        val id = UUID.randomUUID().toString()

        coroutineScope {
            val received = expect(b) { it.message.msgId == id }
            val text = "你好，集成测试 <&> 😀"
            assertThat(a.sendMessage(MessageCodec.chat(jidB, id, text, MessageContent.Text), TIMEOUT)).isTrue()

            val msg = received.await().message
            assertThat(msg.body).isEqualTo(text)
            assertThat(msg.content).isEqualTo(MessageContent.Text)
            assertThat(msg.from).startsWith(jidA)
            assertThat(msg.requestReceipt).isTrue()
            assertThat(msg.markable).isTrue()

            // B 回送达回执，A 收到
            val receipt = expect(a) { it.message.receivedId == id }
            b.sendMessage(MessageCodec.receipt(msg.from!!, id, UUID.randomUUID().toString()), TIMEOUT)
            assertThat(receipt.await().message.from).startsWith(jidB)
        }
    }

    /** 非文本内容（含合并转发的嵌套 message）经服务器转发后完整 */
    @Test
    fun richContent_survivesServer(): Unit = runBlocking {
        val a = login(5)
        val b = login(6)
        val content = MessageContent.Merged(
            "聊天记录",
            listOf(
                cn.android666.im.core.xmpp.extension.msg.ForwardedItem(
                    "o1",
                    jidA,
                    "[名片] 王五",
                    MessageContent.Card("10003@${ItServer.domain}", "王五"),
                    1_791_853_200_000,
                ),
            ),
        )
        val id = UUID.randomUUID().toString()

        coroutineScope {
            val received = expect(b) { it.message.msgId == id }
            a.sendMessage(MessageCodec.chat(jidB, id, MessageCodec.fallbackBody(content), content), TIMEOUT)

            assertThat(received.await().message.content).isEqualTo(content)
        }
    }

    /** 离线消息（开发计划 4.4，协议规范 §11.3）：对方离线时发送，对方上线发送初始 presence 后收到，带 delay 时间 */
    @Test
    fun offlineMessage_deliveredAfterPresenceWithDelay(): Unit = runBlocking {
        val a = login(5)
        val id = UUID.randomUUID().toString()
        // B 不在线：服务器存为离线消息
        assertThat(a.sendMessage(MessageCodec.chat(jidB, id, "离线消息", MessageContent.Text), TIMEOUT)).isTrue()

        val b = SmackXmppClient(
            serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
            trustManager = ItServer.trustManager,
            dispatchers = dispatchers,
            clock = SystemTimeClock,
            logStanzas = { false },
        ).also { clients += it }
        b.login(Credential(ItServer.user(6), ItServer.testUserPassword))
        coroutineScope {
            val received = expect(b) { it.message.msgId == id }
            b.sendPresence()

            val msg = received.await().message
            assertThat(msg.body).isEqualTo("离线消息")
            assertThat(msg.delayStamp).isNotNull()
            assertThat(msg.delayStamp!!).isAtMost(System.currentTimeMillis() + 60_000)
        }
    }

    /** 已读标记（开发计划 4.9，协议规范 §8.2）：B 对 A 的消息发送 displayed，A 收到；带 store 提示进入存档 */
    @Test
    fun displayed_deliveredToSender(): Unit = runBlocking {
        val a = login(5)
        val b = login(6)
        val id = UUID.randomUUID().toString()

        coroutineScope {
            val received = expect(b) { it.message.msgId == id }
            a.sendMessage(MessageCodec.chat(jidB, id, "请阅", MessageContent.Text), TIMEOUT)
            received.await()

            val displayed = expect(a) { it.message.displayedId == id }
            b.sendMessage(MessageCodec.displayed(jidA, id, UUID.randomUUID().toString()), TIMEOUT)
            assertThat(displayed.await().message.from).startsWith(jidB)
        }
    }

    /**
     * Carbons 多端同步（开发计划 4.6，协议规范 §11.1）：A 的 android 端和 pc 端同时在线，
     * pc 端发出的、pc 端收到的单聊消息都抄送到 android 端；pc 端发给"文件传输助手"（本人 bare JID）的消息 android 端也能收到。
     */
    @Test
    fun carbons_syncBetweenResources(): Unit = runBlocking {
        val android = login(5).also { it.enableCarbons() }
        val pc = login(5, resource = "pc").also { it.enableCarbons() }
        val b = login(6)

        coroutineScope {
            // pc 发给 B → android 收到 sent 抄送
            val sentId = UUID.randomUUID().toString()
            val sentCopy = expect(android) { it.message.msgId == sentId }
            pc.sendMessage(MessageCodec.chat(jidB, sentId, "pc 发的", MessageContent.Text), TIMEOUT)
            val sent = sentCopy.await()
            assertThat((sent.envelope as MessageEnvelope.Carbon).sent).isTrue()
            assertThat(sent.envelope.outerFrom).isEqualTo(jidA)
            assertThat(sent.message.to).isEqualTo(jidB)

            // B 发给 A 的 pc 端（完整 JID）→ android 收到 received 抄送
            val receivedId = UUID.randomUUID().toString()
            val receivedCopy = expect(android) { it.message.msgId == receivedId }
            b.sendMessage(MessageCodec.chat("$jidA/pc", receivedId, "发给 pc", MessageContent.Text), TIMEOUT)
            val received = receivedCopy.await()
            assertThat((received.envelope as MessageEnvelope.Carbon).sent).isFalse()
            assertThat(received.message.from).startsWith(jidB)

            // pc 发给文件传输助手（本人 bare JID）→ android 收到（直接路由或抄送）
            val selfId = UUID.randomUUID().toString()
            val selfCopy = expect(android) { it.message.msgId == selfId }
            pc.sendMessage(MessageCodec.chat(jidA, selfId, "传给自己", MessageContent.Text), TIMEOUT)
            assertThat(selfCopy.await().message.body).isEqualTo("传给自己")
        }
    }

    private companion object {
        /** 单次等待上限（毫秒） */
        const val TIMEOUT = 15_000L
    }
}
