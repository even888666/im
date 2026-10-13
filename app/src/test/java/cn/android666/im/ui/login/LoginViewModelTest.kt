package cn.android666.im.ui.login

import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.SessionTestRig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [LoginViewModel] 测试 */
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 回显上次登录的工号 */
    @Test
    fun savedUsername_prefilled() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.credentialStore.save(Credential("10086", "p"))
        rig.credentialStore.clearPassword()

        val vm = LoginViewModel(rig.sessionManager, rig.credentialStore)
        runCurrent()

        assertThat(vm.state.value.savedUsername).isEqualTo("10086")
    }

    @Test
    fun emptyInput_showsFieldErrors() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        val vm = LoginViewModel(rig.sessionManager, rig.credentialStore)

        vm.login("  ", "")

        assertThat(vm.state.value.usernameError).isEqualTo(R.string.login_username_empty)
        assertThat(vm.state.value.passwordError).isEqualTo(R.string.login_password_empty)
        assertThat(rig.client.calls).isEmpty()
    }

    @Test
    fun invalidUsername_rejected() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        val vm = LoginViewModel(rig.sessionManager, rig.credentialStore)

        vm.login("张三", "p")

        assertThat(vm.state.value.usernameError).isEqualTo(R.string.login_username_invalid)
    }

    /** 成功：工号去空格转小写后登录，进入主界面 */
    @Test
    fun success_trimsLowercasesAndGoesMain() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        val vm = LoginViewModel(rig.sessionManager, rig.credentialStore)

        vm.events.test {
            vm.login("  ABC.01 ", "Passw0rd")
            assertThat(vm.state.value.loading).isTrue()
            assertThat(awaitItem()).isEqualTo(LoginEvent.GoMain)
        }
        assertThat(rig.client.lastCredential!!.username).isEqualTo("abc.01")
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun authFailure_showsError() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.client.loginResults += XmppException.AuthenticationFailed()
        val vm = LoginViewModel(rig.sessionManager, rig.credentialStore)

        vm.login("10001", "wrong")
        runCurrent()

        assertThat(vm.state.value.error).isEqualTo(R.string.error_auth_failed)
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun networkFailure_showsError() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.client.loginResults += XmppException.ConnectionFailed("refused")
        val vm = LoginViewModel(rig.sessionManager, rig.credentialStore)

        vm.login("10001", "p")
        runCurrent()

        assertThat(vm.state.value.error).isEqualTo(R.string.error_connection_failed)
    }
}
