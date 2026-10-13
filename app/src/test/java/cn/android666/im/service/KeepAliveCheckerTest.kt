package cn.android666.im.service

import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.service.KeepAliveChecker.Result
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [KeepAliveChecker] 测试 */
class KeepAliveCheckerTest {

    private var running = false
    private var hasCredential = true
    private var state: ConnectionState = ConnectionState.Authenticated(resumed = false)
    private var startError: Exception? = null
    private val started = mutableListOf<String>()
    private var reconnects = 0

    private val checker = KeepAliveChecker(
        isServiceRunning = { running },
        hasSavedCredential = { hasCredential },
        startService = { reason ->
            startError?.let { throw it }
            started += reason
        },
        connectionState = { state },
        reconnectNow = { reconnects++ },
    )

    @Test
    fun noCredential_doNothing() = runTest {
        hasCredential = false

        assertThat(checker.check("定期巡检")).isEqualTo(Result.NO_CREDENTIAL)
        assertThat(started).isEmpty()
    }

    @Test
    fun serviceNotRunning_started() = runTest {
        assertThat(checker.check("开机")).isEqualTo(Result.SERVICE_STARTED)
        assertThat(started).containsExactly("开机")
    }

    /** Android 12+ 后台启动前台服务受限：不崩溃，返回结果供引导用户设置 */
    @Test
    fun startNotAllowed_handled() = runTest {
        startError = IllegalStateException("startForegroundService() not allowed")

        assertThat(checker.check("定期巡检")).isEqualTo(Result.START_NOT_ALLOWED)
    }

    @Test
    fun runningAndConnected_healthy() = runTest {
        running = true

        assertThat(checker.check("定期巡检")).isEqualTo(Result.HEALTHY)
        assertThat(reconnects).isEqualTo(0)
    }

    /** 服务在运行但连接断了（例如息屏后网络被限制）：触发立即重连 */
    @Test
    fun runningButDisconnected_reconnect() = runTest {
        running = true
        state = ConnectionState.Disconnected(DisconnectReason.Network(null))

        assertThat(checker.check("定期巡检")).isEqualTo(Result.RECONNECT_TRIGGERED)
        assertThat(reconnects).isEqualTo(1)
    }

    /** 被挤下线等不可重连的原因：不重连 */
    @Test
    fun runningButKickedOut_noReconnect() = runTest {
        running = true
        state = ConnectionState.Disconnected(DisconnectReason.Conflict)

        assertThat(checker.check("定期巡检")).isEqualTo(Result.HEALTHY)
        assertThat(reconnects).isEqualTo(0)
    }
}
