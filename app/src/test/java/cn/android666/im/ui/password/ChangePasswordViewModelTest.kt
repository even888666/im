package cn.android666.im.ui.password

import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.data.session.SessionState
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.SessionTestRig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [ChangePasswordViewModel] 测试 */
class ChangePasswordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 登录后进入"必须改密"状态 */
    private suspend fun TestScope.loggedInMustChange(): SessionTestRig {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.mustChangePassword = true
        rig.sessionManager.login(Credential("10001", "Init1234"))
        runCurrent()
        assertThat(rig.sessionManager.state.value).isInstanceOf(SessionState.MustChangePassword::class.java)
        return rig
    }

    @Test
    fun ruleViolation_localErrorNoRequest() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedInMustChange()
        val vm = ChangePasswordViewModel(rig.sessionManager)

        vm.submit("short1", "short1")

        assertThat(vm.state.value.error).isEqualTo(R.string.password_rule_length)
        assertThat(rig.client.calls).doesNotContain("iq:query")
    }

    @Test
    fun success_done() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedInMustChange()
        val vm = ChangePasswordViewModel(rig.sessionManager)

        vm.events.test {
            vm.submit("NewPass123", "NewPass123")
            assertThat(awaitItem()).isEqualTo(ChangePasswordEvent.Done)
        }
        runCurrent()
        assertThat(rig.credentialStore.load()!!.password).isEqualTo("NewPass123")
        assertThat(rig.sessionManager.state.value).isInstanceOf(SessionState.Ready::class.java)
    }

    /** 服务器判定密码太弱（weak-password）：显示协议规范 §16 的提示 */
    @Test
    fun serverWeakPassword_showsError() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedInMustChange()
        rig.client.iqResponder = { throw XmppException.StanzaError("not-acceptable", "weak-password") }
        val vm = ChangePasswordViewModel(rig.sessionManager)

        vm.submit("NewPass123", "NewPass123")
        runCurrent()

        assertThat(vm.state.value.error).isEqualTo(R.string.error_weak_password)
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun logout_loggedOut() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = loggedInMustChange()
        val vm = ChangePasswordViewModel(rig.sessionManager)

        vm.events.test {
            vm.logout()
            assertThat(awaitItem()).isEqualTo(ChangePasswordEvent.LoggedOut)
        }
        assertThat(rig.sessionManager.state.value).isEqualTo(SessionState.LoggedOut)
    }
}
