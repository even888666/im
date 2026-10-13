package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.OwnPresence
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.extension.presence.PresenceQueryRequest
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 在线状态集成测试（开发计划 3.20）：App 客户端发送自定义状态（协议规范 §5.2），另一端用 presence-query 查到。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*PresenceIT*'`
 *
 * 使用测试账号 10012、10013。
 */
class PresenceIT {

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    private val clients = mutableListOf<SmackXmppClient>()

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { runCatching { it.logout() } }
    }

    /**
     * 登录。
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
    }

    @Test
    fun customStatus_visibleViaPresenceQuery() = runBlocking {
        val a = login(12)
        a.sendPresence(OwnPresence(show = "dnd", status = "下午 3 点前在开会", code = "meeting"))
        val b = login(13)
        val jidA = "${ItServer.user(12)}@${ItServer.domain}"

        // presence 异步处理，最多等 2 秒
        var resource = b.sendIq(PresenceQueryRequest(listOf(jidA))).single().resources.singleOrNull()
        for (i in 0 until 20) {
            if (resource?.code == "meeting") break
            delay(100)
            resource = b.sendIq(PresenceQueryRequest(listOf(jidA))).single().resources.singleOrNull()
        }
        assertThat(resource?.resource).isEqualTo("android")
        assertThat(resource?.show).isEqualTo("dnd")
        assertThat(resource?.status).isEqualTo("下午 3 点前在开会")
        assertThat(resource?.code).isEqualTo("meeting")

        // 改回在线：没有 state 元素
        a.sendPresence()
        delay(300)
        val online = b.sendIq(PresenceQueryRequest(listOf(jidA))).single().resources.single()
        assertThat(online.code).isNull()
        assertThat(online.show).isNull()
    }
}
