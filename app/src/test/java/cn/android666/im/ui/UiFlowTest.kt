package cn.android666.im.ui

import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.App
import cn.android666.im.R
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.login.LoginActivity
import cn.android666.im.ui.main.MainActivity
import cn.android666.im.ui.splash.SplashActivity
import com.google.android.material.textfield.TextInputLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * 页面跳转与界面校验的 Robolectric 测试（开发计划 2.17 ~ 2.23），使用真实的 [App] 依赖容器。
 *
 * ViewModel 中的协程运行在主线程，DataStore 读写在 IO 线程，所以用 [waitUntil] 循环推进主线程直到条件满足。
 */
@RunWith(RobolectricTestRunner::class)
class UiFlowTest {

    /**
     * 推进主线程消息队列，直到 [condition] 成立或超时。
     *
     * @param condition 等待的条件
     */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时")
    }

    /**
     * 当前应用最近一次启动的 Activity。
     *
     * @return 启动的 Intent；没有时为 null
     */
    private fun nextStartedActivity(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<App>()).peekNextStartedActivity()

    /** 首次启动：弹出隐私政策（不可取消）；同意后没有保存的凭证，进入登录页 */
    @Test
    fun splash_firstLaunch_privacyThenLogin() {
        ActivityScenario.launch(SplashActivity::class.java).use { scenario ->
            waitUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("privacy") as MessageDialogFragment
                assertThat(fragment.isCancelable).isFalse()
            }

            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

            waitUntil { nextStartedActivity() != null }
            assertThat(nextStartedActivity()!!.component!!.className).isEqualTo(LoginActivity::class.java.name)
        }
    }

    /** 登录页：工号为空、密码为空时显示输入框错误，不发起登录 */
    @Test
    fun login_emptyInput_showsErrors() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.loginButton).performClick()
            }
            waitUntil {
                var shown = false
                scenario.onActivity { activity ->
                    shown = activity.findViewById<TextInputLayout>(R.id.usernameLayout).error != null
                }
                shown
            }
            scenario.onActivity { activity ->
                assertThat(activity.findViewById<TextInputLayout>(R.id.usernameLayout).error.toString())
                    .isEqualTo(activity.getString(R.string.login_username_empty))
                assertThat(activity.findViewById<TextInputLayout>(R.id.passwordLayout).error.toString())
                    .isEqualTo(activity.getString(R.string.login_password_empty))
            }
        }
    }

    /** 未登录时打开主界面（例如被系统重建）：回到登录页 */
    @Test
    fun main_loggedOut_goesLogin() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil { nextStartedActivity() != null }
            assertThat(nextStartedActivity()!!.component!!.className).isEqualTo(LoginActivity::class.java.name)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
