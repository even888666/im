package cn.android666.im.ui.main

import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.network.NetworkStatus
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.SessionTestRig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [MainViewModel] 测试：连接状态条、会话被结束、退出登录 */
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private suspend fun TestScope.loggedIn(): SessionTestRig {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.sessionManager.login(Credential("10001", "p"))
        runCurrent()
        return rig
    }

    private fun newVm(rig: SessionTestRig) =
        MainViewModel(rig.sessionManager, rig.client.connectionState, rig.network.status)

    @Test
    fun connected_noBanner_usernameShown() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedIn()
        val vm = newVm(rig)
        runCurrent()

        assertThat(vm.banner.value).isNull()
        assertThat(vm.username.value).isEqualTo("10001")
    }

    /** 断网后显示"网络不可用"，网络恢复并重连后状态条消失 */
    @Test
    fun banner_followsConnectionAndNetwork() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedIn()
        val vm = newVm(rig)
        rig.network.status.value = NetworkStatus.NONE
        rig.client.reconnectResults += XmppException.ConnectionFailed("x")
        rig.client.simulateDisconnect(DisconnectReason.Network(null))
        runCurrent()
        assertThat(vm.banner.value).isEqualTo(R.string.banner_no_network)

        rig.network.status.value = NetworkStatus(available = true, networkId = "wifi")
        runCurrent()
        assertThat(vm.banner.value).isNull()
    }

    /** 被挤下线：发出提示事件（开发计划 2.22） */
    @Test
    fun conflict_sessionEndedEvent() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedIn()
        val vm = newVm(rig)

        vm.events.test {
            rig.client.simulateDisconnect(DisconnectReason.Conflict)
            assertThat(awaitItem()).isEqualTo(MainEvent.SessionEnded(R.string.error_conflict))
        }
    }

    @Test
    fun mustChangePassword_event() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.mustChangePassword = true
        val vm = newVm(rig)

        vm.events.test {
            rig.sessionManager.login(Credential("10001", "p"))
            // 首个状态是 LoggedOut → GoLogin（主界面不会在未登录时打开，这里只取改密事件）。
            // StateFlow 会合并中间状态，GoLogin 不一定出现，所以跳过它再断言
            var event = awaitItem()
            if (event == MainEvent.GoLogin) event = awaitItem()
            assertThat(event).isEqualTo(MainEvent.GoChangePassword)
        }
    }

    /** 退出登录（开发计划 2.23）：会话变为 LoggedOut，回到登录页 */
    @Test
    fun logout_goLogin() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedIn()
        val vm = newVm(rig)

        vm.events.test {
            vm.logout()
            assertThat(awaitItem()).isEqualTo(MainEvent.GoLogin)
        }
        assertThat(rig.client.calls.last()).isEqualTo("logout")
        assertThat(rig.credentialStore.load()!!.password).isNull()
    }

    /** "消息" Tab 角标：有订阅者时跟随总未读数 */
    @Test
    fun totalUnread_followsRepository() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedIn()
        val unread = kotlinx.coroutines.flow.MutableStateFlow(0)
        val vm = MainViewModel(rig.sessionManager, rig.client.connectionState, rig.network.status, unread)

        vm.totalUnread.test {
            assertThat(awaitItem()).isEqualTo(0)
            unread.value = 7
            assertThat(awaitItem()).isEqualTo(7)
        }
    }
}
