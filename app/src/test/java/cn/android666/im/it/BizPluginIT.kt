package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.extension.msg.IncomingMessage
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.extension.msg.MessageError
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItAdmin
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * 业务扩展插件（biz）集成测试（开发计划 4.19 ~ 4.21）：敏感词拒绝投递、撤回校验。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*BizPluginIT*'`
 *
 * 使用测试账号 10011、10012、10013。
 */
class BizPluginIT {

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    private val clients = mutableListOf<SmackXmppClient>()

    private val jidB = "${ItServer.user(12)}@${ItServer.domain}"

    /** 本次测试使用的敏感词（随机，避免与其他测试或手工添加的词冲突） */
    private val word = "测试敏感词" + UUID.randomUUID().toString().take(6)

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { runCatching { it.logout() } }
        runCatching { ItAdmin.sensitiveWords("delete", listOf(word)) }
        Unit
    }

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

    /** 开始等待第一条满足条件的消息 */
    private fun CoroutineScope.expect(client: SmackXmppClient, predicate: (IncomingMessage) -> Boolean): Deferred<IncomingMessage?> =
        async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(TIMEOUT) { client.incomingMessages.map { MessageCodec.parse(it) }.first(predicate) }
        }

    /** 4.21：包含敏感词（中间插入空格也算）→ 发送方收到 policy-violation + sensitive-word，接收方收不到 */
    @Test
    fun sensitiveWord_rejected(): Unit = runBlocking {
        ItAdmin.sensitiveWords("add", listOf(word))
        val a = login(11)
        val b = login(12)
        val id = UUID.randomUUID().toString()
        val spaced = word.toList().joinToString(" ")

        coroutineScope {
            val error = expect(a) { it.message.type == "error" && it.message.msgId == id }
            val delivered = expect(b) { it.message.msgId == id }
            a.sendMessage(MessageCodec.chat(jidB, id, "前缀 $spaced 后缀", MessageContent.Text), TIMEOUT)

            assertThat(error.await()!!.message.error).isEqualTo(MessageError("policy-violation", "sensitive-word"))
            assertThat(delivered.await()).isNull()
        }

        // 删除后可以发送
        ItAdmin.sensitiveWords("delete", listOf(word))
        val id2 = UUID.randomUUID().toString()
        coroutineScope {
            val delivered = expect(b) { it.message.msgId == id2 }
            a.sendMessage(MessageCodec.chat(jidB, id2, "现在可以了 $word", MessageContent.Text), TIMEOUT)
            assertThat(delivered.await()).isNotNull()
        }
    }

    /** 4.20：本人 2 分钟内撤回允许；他人代撤回 → retract-forbidden；未知消息 → retract-timeout */
    @Test
    fun retract_validated(): Unit = runBlocking {
        val a = login(11)
        val b = login(12)
        val c = login(13)
        val id = UUID.randomUUID().toString()

        coroutineScope {
            val original = expect(b) { it.message.msgId == id }
            a.sendMessage(MessageCodec.chat(jidB, id, "原消息", MessageContent.Text), TIMEOUT)
            assertThat(original.await()).isNotNull()
        }
        // 别人（C）撤回 A 发给 B 的消息
        coroutineScope {
            val retractId = UUID.randomUUID().toString()
            val error = expect(c) { it.message.type == "error" && it.message.msgId == retractId }
            c.sendMessage(MessageCodec.retract(jidB, id, "王五撤回了一条消息", retractId), TIMEOUT)
            assertThat(error.await()!!.message.error).isEqualTo(MessageError("forbidden", "retract-forbidden"))
        }
        // 撤回不存在的消息
        coroutineScope {
            val retractId = UUID.randomUUID().toString()
            val error = expect(a) { it.message.type == "error" && it.message.msgId == retractId }
            a.sendMessage(MessageCodec.retract(jidB, "no-such-message", "张三撤回了一条消息", retractId), TIMEOUT)
            assertThat(error.await()!!.message.error).isEqualTo(MessageError("not-allowed", "retract-timeout"))
        }
        // 本人撤回：B 收到
        coroutineScope {
            val retract = expect(b) { it.message.retractId == id }
            a.sendMessage(MessageCodec.retract(jidB, id, "张三撤回了一条消息", UUID.randomUUID().toString()), TIMEOUT)
            assertThat(retract.await()).isNotNull()
        }
    }

    private companion object {
        /** 单次等待上限（毫秒） */
        const val TIMEOUT = 10_000L
    }
}
