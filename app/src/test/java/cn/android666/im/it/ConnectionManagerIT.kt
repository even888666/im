package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.connection.ConnectionEvent
import cn.android666.im.core.xmpp.connection.XmppConnectionManager
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.testing.FakeNetworkMonitor
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [XmppConnectionManager] + 真实 [SmackXmppClient] 的集成测试（开发计划 2.6）：
 * 网络中断后自动重连并恢复会话；第二台设备登录后停止重连。
 * 使用测试账号 10010（冲突测试使用 10003）。
 */
class ConnectionManagerIT {

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<SmackXmppClient>()

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { runCatching { it.logout() } }
        scope.cancel()
    }

    private fun newClient() = SmackXmppClient(
        serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
        trustManager = ItServer.trustManager,
        dispatchers = dispatchers,
        clock = SystemTimeClock,
        logStanzas = { false },
    ).also { clients += it }

    private fun credential(index: Int) = Credential(ItServer.user(index), ItServer.testUserPassword)

    @Test
    fun networkDrop_reconnectsAutomaticallyWithResume() = runBlocking {
        val client = newClient()
        val manager = XmppConnectionManager(client, FakeNetworkMonitor(), scope)
        manager.login(credential(10))

        val reconnected = async(start = CoroutineStart.UNDISPATCHED) {
            manager.events.first { it is ConnectionEvent.Connected && it.isReconnect }
        }

        client.simulateNetworkDropForTest()

        val event = withTimeout(15_000) { reconnected.await() }
        assertThat(event).isEqualTo(ConnectionEvent.Connected(resumed = true, isReconnect = true))
        assertThat(manager.state.value).isEqualTo(ConnectionState.Authenticated(resumed = true))
    }

    @Test
    fun secondDeviceLogin_stopsWithConflict() = runBlocking {
        val manager = XmppConnectionManager(newClient(), FakeNetworkMonitor(), scope)
        manager.login(credential(3))
        // events 不重放，必须在触发之前开始订阅，否则很快到达的 Stopped 事件会被错过
        val stopped = async(start = CoroutineStart.UNDISPATCHED) {
            manager.events.first { it is ConnectionEvent.Stopped }
        }

        newClient().login(credential(3))

        val event = withTimeout(15_000) { stopped.await() }
        assertThat(event).isEqualTo(ConnectionEvent.Stopped(DisconnectReason.Conflict))
    }
}
