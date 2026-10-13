package cn.android666.im.core.xmpp.connection

import cn.android666.im.core.network.NetworkStatus
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.testing.FakeNetworkMonitor
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [XmppConnectionManager] 测试：用 [FakeXmppClient]、[FakeNetworkMonitor] 和虚拟时间验证重连策略。
 * 退避策略去掉随机抖动，等待时间严格为 0、2、4、8… 秒。
 */
class XmppConnectionManagerTest {

    private val client = FakeXmppClient()
    private val network = FakeNetworkMonitor()
    private val credential = Credential("10001", "p")

    /** 收集到的事件 */
    private val events = mutableListOf<ConnectionEvent>()

    /**
     * 创建被测对象并开始收集事件。
     *
     * @param scope 运行连接管理与事件收集的作用域（runTest 的 backgroundScope，测试结束自动取消）
     * @return 连接管理器
     */
    private fun TestScope.newManager(scope: CoroutineScope = backgroundScope): XmppConnectionManager {
        val manager = XmppConnectionManager(client, network, scope) { ReconnectPolicy(jitterRatio = 0.0) }
        backgroundScope.launch { manager.events.collect { events += it } }
        runCurrent()
        return manager
    }

    @Test
    fun login_success_emitsConnected() = runTest {
        val manager = newManager()

        val result = manager.login(credential)
        runCurrent()

        assertThat(result.resumed).isFalse()
        assertThat(events).containsExactly(ConnectionEvent.Connected(resumed = false, isReconnect = false))
    }

    @Test
    fun login_failure_throwsAndDoesNotRetry() = runTest {
        val manager = newManager()
        client.loginResults += XmppException.AuthenticationFailed()

        val e = runCatching { manager.login(credential) }.exceptionOrNull()
        advanceTimeBy(120_000)

        assertThat(e).isInstanceOf(XmppException.AuthenticationFailed::class.java)
        assertThat(client.calls).containsExactly("login:10001")
    }

    /** 网络原因断线：立即重连，流恢复成功 */
    @Test
    fun networkDisconnect_reconnectsImmediately() = runTest {
        val manager = newManager()
        manager.login(credential)

        client.simulateDisconnect(DisconnectReason.Network(null))
        runCurrent()

        assertThat(client.calls).containsExactly("login:10001", "reconnect").inOrder()
        assertThat(events.last()).isEqualTo(ConnectionEvent.Connected(resumed = true, isReconnect = true))
        assertThat(manager.state.value).isEqualTo(ConnectionState.Authenticated(resumed = true))
    }

    /** 重连失败按 0、2、4 秒退避，成功后重置 */
    @Test
    fun reconnectFailures_backOff() = runTest {
        val manager = newManager()
        manager.login(credential)
        client.reconnectResults += XmppException.ConnectionFailed("超时")
        client.reconnectResults += XmppException.ConnectionFailed("超时")

        client.simulateDisconnect(DisconnectReason.Network(null))
        runCurrent()
        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(1) // t=0 第 1 次，失败

        advanceTimeBy(1_999)
        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(1)
        advanceTimeBy(2)
        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(2) // t=2s 第 2 次，失败

        advanceTimeBy(3_998)
        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(2)
        advanceTimeBy(4)
        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(3) // t=6s 第 3 次，成功
        assertThat(events.last()).isEqualTo(ConnectionEvent.Connected(resumed = true, isReconnect = true))

        // 成功后退避已重置：再次断线立即重连
        client.simulateDisconnect(DisconnectReason.Network(null))
        runCurrent()
        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(4)
    }

    /** 被挤下线：不重连，发出 Stopped */
    @Test
    fun conflict_stopsWithoutReconnect() = runTest {
        val manager = newManager()
        manager.login(credential)

        client.simulateDisconnect(DisconnectReason.Conflict)
        advanceTimeBy(120_000)

        assertThat(client.calls).containsExactly("login:10001")
        assertThat(events.last()).isEqualTo(ConnectionEvent.Stopped(DisconnectReason.Conflict))
    }

    @Test
    fun accountDisabled_stopsWithoutReconnect() = runTest {
        val manager = newManager()
        manager.login(credential)

        client.simulateDisconnect(DisconnectReason.AccountDisabled)
        advanceTimeBy(120_000)

        assertThat(client.calls).doesNotContain("reconnect")
        assertThat(events.last()).isEqualTo(ConnectionEvent.Stopped(DisconnectReason.AccountDisabled))
    }

    /** 重连时认证失败（例如在其他设备改了密码）：停止重连 */
    @Test
    fun reconnectAuthFailure_stops() = runTest {
        val manager = newManager()
        manager.login(credential)
        client.reconnectResults += XmppException.AuthenticationFailed()

        client.simulateDisconnect(DisconnectReason.Network(null))
        advanceTimeBy(120_000)

        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(1)
        assertThat(events.last()).isEqualTo(ConnectionEvent.Stopped(DisconnectReason.AuthenticationFailed))
    }

    /** 无网络时不尝试重连，网络恢复后立即重连 */
    @Test
    fun noNetwork_waitsThenReconnectsWhenAvailable() = runTest {
        val manager = newManager()
        manager.login(credential)

        network.status.value = NetworkStatus.NONE
        client.simulateDisconnect(DisconnectReason.Network(null))
        advanceTimeBy(300_000)
        assertThat(client.calls).doesNotContain("reconnect")

        network.status.value = NetworkStatus(available = true, networkId = "mobile")
        runCurrent()
        assertThat(client.calls).contains("reconnect")
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Authenticated::class.java)
    }

    /** 退避等待期间网络恢复（或回到前台）：不必等满，立即重连 */
    @Test
    fun reconnectNow_duringBackoff_reconnectsImmediately() = runTest {
        val manager = newManager()
        manager.login(credential)
        repeat(3) { client.reconnectResults += XmppException.ConnectionFailed("超时") }

        client.simulateDisconnect(DisconnectReason.Network(null))
        advanceTimeBy(2_001 + 4_001) // 失败 3 次（t=0、2s、6s），下一次在 t=14s
        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(3)

        manager.reconnectNow()
        runCurrent()

        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(4)
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Authenticated::class.java)
    }

    /** 后台登录：网络原因失败时持续重试，成功后发出 Connected（非重连） */
    @Test
    fun start_retriesLoginUntilSuccess() = runTest {
        val manager = newManager()
        client.loginResults += XmppException.ConnectionFailed("无法连接")
        client.loginResults += XmppException.Timeout()

        manager.start(credential)
        advanceTimeBy(10_000)

        assertThat(client.calls).containsExactly("login:10001", "login:10001", "login:10001")
        assertThat(events).containsExactly(ConnectionEvent.Connected(resumed = false, isReconnect = false))
    }

    /** 后台登录遇到认证失败：停止，不再重试 */
    @Test
    fun start_authFailure_stops() = runTest {
        val manager = newManager()
        client.loginResults += XmppException.AuthenticationFailed()

        manager.start(credential)
        advanceTimeBy(120_000)

        assertThat(client.calls).containsExactly("login:10001")
        assertThat(events).containsExactly(ConnectionEvent.Stopped(DisconnectReason.AuthenticationFailed))
    }

    /** 退出登录后，即使出现断线也不再重连 */
    @Test
    fun stop_logsOutAndStopsSupervising() = runTest {
        val manager = newManager()
        manager.login(credential)

        manager.stop()
        client.simulateDisconnect(DisconnectReason.Network(null))
        advanceTimeBy(120_000)

        assertThat(client.calls).containsExactly("login:10001", "logout").inOrder()
    }

    /** 客户端没有可恢复的连接时，重连改为完整登录 */
    @Test
    fun reconnectNotConnected_fallsBackToLogin() = runTest {
        val manager = newManager()
        manager.login(credential)
        client.reconnectResults += XmppException.NotConnected()

        client.simulateDisconnect(DisconnectReason.Network(null))
        runCurrent()

        assertThat(client.calls).containsExactly("login:10001", "reconnect", "login:10001").inOrder()
        assertThat(events.last()).isEqualTo(ConnectionEvent.Connected(resumed = false, isReconnect = true))
    }

    /** 已连接时切换网络（例如 Wi-Fi 切到移动网络）：只检测连接，连接仍可用时不重连 */
    @Test
    fun networkChange_whileConnected_checksAliveOnly() = runTest {
        val manager = newManager()
        manager.login(credential)
        // 让网络监听协程先开始收集，之后的网络变化才会被处理
        runCurrent()

        network.status.value = NetworkStatus(available = true, networkId = "mobile")
        runCurrent()

        assertThat(client.calls).containsExactly("login:10001", "checkAlive").inOrder()
        assertThat(manager.state.value).isEqualTo(ConnectionState.Authenticated(resumed = false))
    }

    /** 切换网络后检测发现连接已失效：立即重连 */
    @Test
    fun networkChange_connectionDead_reconnects() = runTest {
        val manager = newManager()
        manager.login(credential)
        // 让网络监听协程先开始收集，之后的网络变化才会被处理
        runCurrent()
        client.aliveResults += false

        network.status.value = NetworkStatus(available = true, networkId = "mobile")
        runCurrent()

        assertThat(client.calls).containsExactly("login:10001", "checkAlive", "reconnect").inOrder()
        assertThat(events.last()).isEqualTo(ConnectionEvent.Connected(resumed = true, isReconnect = true))
    }
}
