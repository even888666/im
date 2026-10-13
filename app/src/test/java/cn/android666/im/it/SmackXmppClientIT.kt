package cn.android666.im.it

import cn.android666.im.core.common.DefaultDispatcherProvider
import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.security.RootCaTrust
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.account.AccountStatusRequest
import cn.android666.im.core.xmpp.extension.account.ChangePasswordRequest
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import javax.net.ssl.X509TrustManager

/**
 * [SmackXmppClient] 集成测试：连接本地 Openfire（server/up.sh），验证开发计划 2.3、2.4、2.8。
 *
 * 运行：`./gradlew testDebugUnitTest -Pim.it=true --tests '*SmackXmppClientIT*'`
 * 使用测试账号 10010（冲突测试使用 10003）。
 */
class SmackXmppClientIT {

    /** JVM 上没有 Android 主线程，Main 也用 IO 代替（本类不会用到 Main） */
    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    /** 本测试创建的客户端，结束时统一退出 */
    private val clients = mutableListOf<SmackXmppClient>()

    /** 报文日志输出 */
    private val stanzaLogs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { runCatching { it.logout() } }
    }

    /**
     * 创建客户端。
     *
     * @param trustManager 信任的根证书，默认开发环境根证书
     * @return 客户端
     */
    private fun newClient(trustManager: X509TrustManager = ItServer.trustManager) = SmackXmppClient(
        serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
        trustManager = trustManager,
        dispatchers = dispatchers,
        clock = SystemTimeClock,
        logStanzas = { true },
        stanzaLogSink = { stanzaLogs += it },
    ).also { clients += it }

    private fun credential(index: Int = 10) = Credential(ItServer.user(index), ItServer.testUserPassword)

    @Test
    fun login_success_authenticated() = runBlocking {
        val client = newClient()

        val result = client.login(credential())

        assertThat(result.resumed).isFalse()
        assertThat(client.connectionState.value).isEqualTo(ConnectionState.Authenticated(resumed = false))
    }

    /** 2.9：登录后的基础步骤：启用 Carbons、查询服务器时间、能力发现 */
    @Test
    fun postLoginSteps_carbonsTimeCapabilities() = runBlocking {
        val client = newClient()
        client.login(credential())

        client.enableCarbons()

        val time = client.queryServerTime()
        // 服务器与测试运行在同一台机器上，时钟偏差应很小
        val offset = ServerClock(SystemTimeClock).calibrate(time.serverMillis, time.requestSentAtMillis, time.roundTripMillis)
        assertThat(kotlin.math.abs(offset)).isLessThan(2_000L)
        assertThat(time.roundTripMillis).isAtLeast(0L)

        val caps = client.discoverCapabilities()
        assertThat(caps.mam).isTrue()
        assertThat(caps.carbons).isTrue()
        assertThat(caps.externalServices).isTrue()
        assertThat(caps.maxUploadFileSize).isEqualTo(104_857_600L)
        // 自研插件尚未开发（开发计划 M3），对应功能不可用
        assertThat(caps.org).isFalse()
    }

    /** 2.11：通过自定义 IQ 修改密码（XEP-0077），之后用新密码登录，再改回原密码 */
    @Test
    fun changePassword_viaSendIq_thenLoginWithNewPassword() = runBlocking {
        val user = ItServer.user(9)
        val newPassword = "Abc${System.currentTimeMillis() % 100_000}x9"
        val client = newClient()
        client.login(Credential(user, ItServer.testUserPassword))

        client.sendIq(ChangePasswordRequest(user, newPassword))
        client.logout()

        val relogin = newClient()
        relogin.login(Credential(user, newPassword))
        // 改回原密码，保证测试可重复运行
        relogin.sendIq(ChangePasswordRequest(user, ItServer.testUserPassword))
        // 日志中不能出现新旧密码（ChangePasswordRequest 的内容是自定义 IQ，由 Smack 调试器记录时需脱敏）
        val logs = synchronized(stanzaLogs) { stanzaLogs.toList() }
        assertThat(logs.none { it.contains(newPassword) }).isTrue()
    }

    /**
     * 2.10：查询账号状态。组织架构插件尚未开发（开发计划 M3），Openfire 不认识该命名空间，
     * 返回 stanza error，映射为 XmppException.StanzaError。插件完成后改为验证 mustChangePassword。
     */
    @Test
    fun accountStatus_withoutOrgPlugin_stanzaError() = runBlocking {
        val client = newClient()
        client.login(credential())

        val e = runCatching { client.sendIq(AccountStatusRequest()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(XmppException.StanzaError::class.java)
        assertThat((e as XmppException.StanzaError).condition).isAnyOf("service-unavailable", "feature-not-implemented")
    }

    /** 未登录时调用请求类方法抛出 NotConnected */
    @Test
    fun requestWithoutLogin_notConnected() = runBlocking {
        val e = runCatching { newClient().discoverCapabilities() }.exceptionOrNull()
        assertThat(e).isInstanceOf(XmppException.NotConnected::class.java)
    }

    @Test
    fun login_wrongPassword_authenticationFailed() = runBlocking {
        val client = newClient()

        val e = runCatching { client.login(Credential(ItServer.user(10), "wrong-password")) }.exceptionOrNull()

        assertThat(e).isInstanceOf(XmppException.AuthenticationFailed::class.java)
        assertThat(client.connectionState.value)
            .isEqualTo(ConnectionState.Disconnected(DisconnectReason.AuthenticationFailed))
    }

    /** 2.3：只信任企业根证书，服务器证书不是由信任的根证书签发时 TLS 失败 */
    @Test
    fun login_untrustedRootCa_tlsFailed() = runBlocking {
        val otherCa = RootCaTrust(javaClass.getResourceAsStream("/tls/test-ca.crt")!!)
        val client = newClient(trustManager = otherCa.trustManager)

        val e = runCatching { client.login(credential()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(XmppException.TlsFailed::class.java)
    }

    /** 2.8：同一端在第二台设备登录，第一台收到 conflict，状态为不可重连的 Conflict */
    @Test
    fun secondDeviceSameResource_firstClientDisconnectedWithConflict() = runBlocking {
        val first = newClient()
        first.login(credential(3))

        newClient().login(credential(3))

        val state = withTimeout(10_000) {
            first.connectionState.first { it is ConnectionState.Disconnected }
        }
        assertThat(state).isEqualTo(ConnectionState.Disconnected(DisconnectReason.Conflict))
        assertThat((state as ConnectionState.Disconnected).reason.shouldReconnect).isFalse()
    }

    /** 网络突然中断后重连，通过流恢复回到原会话 */
    @Test
    fun networkDrop_reconnect_resumesStream() = runBlocking {
        val client = newClient()
        client.login(credential())

        client.simulateNetworkDropForTest()
        val result = client.reconnect()

        assertThat(result.resumed).isTrue()
        assertThat(client.connectionState.value).isEqualTo(ConnectionState.Authenticated(resumed = true))
    }

    @Test
    fun logout_stateIdle_thenReconnectNotConnected() = runBlocking {
        val client = newClient()
        client.login(credential())

        client.logout()

        assertThat(client.connectionState.value).isEqualTo(ConnectionState.Idle)
        val e = runCatching { client.reconnect() }.exceptionOrNull()
        assertThat(e).isInstanceOf(XmppException.NotConnected::class.java)
    }

    /**
     * 报文日志：服务器发来的 SASL 挑战与结果已脱敏，密码不出现在日志中；
     * 流管理确认不逐条记录；普通 IQ（绑定 resource）正常记录。
     * （客户端发出的 `<auth>`、`<response>` 等 nonza 不经过调试器回调，根本不会出现在日志中。）
     */
    @Test
    fun stanzaLog_saslMasked_smAckNotLoggedIndividually() = runBlocking {
        val client = newClient()
        client.login(credential())

        val logs = synchronized(stanzaLogs) { stanzaLogs.toList() }
        assertThat(logs.single { it.startsWith("RECV <challenge") }).contains(">***</challenge>")
        assertThat(logs.single { it.startsWith("RECV <success") }).contains(">***</success>")
        assertThat(logs.filter { it.contains("<auth") || it.contains("<response") }.all { it.contains(">***<") }).isTrue()
        assertThat(logs.none { it.contains(ItServer.testUserPassword) }).isTrue()
        assertThat(logs.none { it.startsWith("SENT <r ") || it.startsWith("RECV <a ") || it.startsWith("RECV <r ") }).isTrue()
        assertThat(logs.any { it.startsWith("SENT <iq") && it.contains("<resource>android</resource>") }).isTrue()
        assertThat(logs).contains("已登录：${ItServer.user(10)}@${ItServer.domain}/android")
    }
}
