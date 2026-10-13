package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.extension.msg.IncomingMessage
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
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
     * @return 客户端
     */
    private suspend fun login(index: Int): SmackXmppClient = SmackXmppClient(
        serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
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

    private companion object {
        /** 单次等待上限（毫秒） */
        const val TIMEOUT = 15_000L
    }
}
