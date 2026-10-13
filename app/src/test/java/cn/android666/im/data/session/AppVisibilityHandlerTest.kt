package cn.android666.im.data.session

import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.connection.ReconnectPolicy
import cn.android666.im.core.xmpp.connection.XmppConnectionManager
import cn.android666.im.testing.FakeNetworkMonitor
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [AppVisibilityHandler] 测试 */
class AppVisibilityHandlerTest {

    private val client = FakeXmppClient()

    @Test
    fun background_thenForeground_setsClientState() = runTest {
        val manager = XmppConnectionManager(client, FakeNetworkMonitor(), backgroundScope) { ReconnectPolicy(jitterRatio = 0.0) }
        val handler = AppVisibilityHandler(client, manager, backgroundScope)

        handler.onBackground()
        runCurrent()
        assertThat(client.active).isFalse()

        handler.onForeground()
        runCurrent()
        assertThat(client.active).isTrue()
        assertThat(client.calls).containsExactly("active:false", "active:true").inOrder()
    }

    /** 回到前台时连接断着、正在退避等待：立即重连 */
    @Test
    fun foreground_whileWaitingToReconnect_reconnectsImmediately() = runTest {
        val manager = XmppConnectionManager(client, FakeNetworkMonitor(), backgroundScope) { ReconnectPolicy(jitterRatio = 0.0) }
        val handler = AppVisibilityHandler(client, manager, backgroundScope)
        manager.login(Credential("10001", "p"))
        runCurrent()
        client.reconnectResults += XmppException.ConnectionFailed("超时")
        client.simulateDisconnect(DisconnectReason.Network(null))
        runCurrent() // 第 1 次重连失败，下一次在 2 秒后
        advanceTimeBy(500)
        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(1)

        handler.onForeground()
        runCurrent()

        assertThat(client.calls.count { it == "reconnect" }).isEqualTo(2)
    }

    /** 回到前台的任务依次执行，某个任务失败不影响后面的任务 */
    @Test
    fun foreground_runsTasks_failureIsolated() = runTest {
        val manager = XmppConnectionManager(client, FakeNetworkMonitor(), backgroundScope) { ReconnectPolicy(jitterRatio = 0.0) }
        val executed = mutableListOf<String>()
        val handler = AppVisibilityHandler(
            client, manager, backgroundScope,
            listOf({ executed += "a"; error("模拟失败") }, { executed += "b" }),
        )

        handler.onForeground()
        runCurrent()

        assertThat(executed).containsExactly("a", "b").inOrder()
    }
}
