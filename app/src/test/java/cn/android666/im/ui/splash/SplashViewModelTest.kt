package cn.android666.im.ui.splash

import app.cash.turbine.test
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.SessionTestRig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [SplashViewModel] 测试：隐私政策与启动去向 */
class SplashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun privacyNotAgreed_showPrivacy_thenAgreeGoesLogin() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        val vm = SplashViewModel(rig.settings, rig.sessionManager)

        vm.events.test {
            vm.start()
            assertThat(awaitItem()).isEqualTo(SplashEvent.ShowPrivacy)

            vm.onPrivacyAgreed()
            assertThat(awaitItem()).isEqualTo(SplashEvent.GoLogin)
        }
        assertThat(rig.settings.isPrivacyAgreed(SplashViewModel.PRIVACY_VERSION)).isTrue()
    }

    @Test
    fun privacyRejected_exit() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        val vm = SplashViewModel(rig.settings, rig.sessionManager)

        vm.events.test {
            vm.start()
            awaitItem()
            vm.onPrivacyRejected()
            assertThat(awaitItem()).isEqualTo(SplashEvent.Exit)
        }
    }

    /** 有保存的凭证：开始自动登录并进入主界面 */
    @Test
    fun savedCredential_autoLoginGoMain() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.settings.setPrivacyAgreed(SplashViewModel.PRIVACY_VERSION)
        rig.credentialStore.save(Credential("10001", "p"))
        val vm = SplashViewModel(rig.settings, rig.sessionManager)

        vm.events.test {
            vm.start()
            assertThat(awaitItem()).isEqualTo(SplashEvent.GoMain)
        }
        runCurrent()
        assertThat(rig.client.calls.first()).isEqualTo("login:10001")
    }

    /** 被挤下线后（进程仍在）重新打开：回到登录页，不自动登录 */
    @Test
    fun sessionEnded_goLogin() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.settings.setPrivacyAgreed(SplashViewModel.PRIVACY_VERSION)
        rig.sessionManager.login(Credential("10001", "p"))
        runCurrent()
        rig.client.simulateDisconnect(DisconnectReason.Conflict)
        runCurrent()
        val vm = SplashViewModel(rig.settings, rig.sessionManager)

        vm.events.test {
            vm.start()
            assertThat(awaitItem()).isEqualTo(SplashEvent.GoLogin)
        }
    }

    /** 进程还活着且需要改密：直接进入改密页 */
    @Test
    fun mustChangePassword_goChangePassword() = runTest(mainDispatcherRule.testDispatcher) {
        val rig = SessionTestRig(backgroundScope, tempFolder.root)
        rig.settings.setPrivacyAgreed(SplashViewModel.PRIVACY_VERSION)
        rig.mustChangePassword = true
        rig.sessionManager.login(Credential("10001", "p"))
        runCurrent()
        val vm = SplashViewModel(rig.settings, rig.sessionManager)

        vm.events.test {
            vm.start()
            assertThat(awaitItem()).isEqualTo(SplashEvent.GoChangePassword)
        }
    }
}
