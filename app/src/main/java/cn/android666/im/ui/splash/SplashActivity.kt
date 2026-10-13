package cn.android666.im.ui.splash

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.base.appViewModels
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.base.startAsNewTask
import cn.android666.im.ui.login.LoginActivity
import cn.android666.im.ui.main.MainActivity
import cn.android666.im.ui.password.ChangePasswordActivity

/**
 * 启动页（开发计划 2.17、2.18）：隐私政策弹窗，然后按 [SplashViewModel] 的判断进入登录页、主界面或改密页。
 *
 * 本页面没有自己的界面内容（只是路由，系统启动画面之后直接跳转），不是 Lint 所说的"自定义闪屏页"，
 * 不会出现两个启动画面，所以忽略 CustomSplashScreen 警告。
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val log = Logs.file(TAG)

    private val viewModel by appViewModels { SplashViewModel(it.appSettingsStore, it.sessionManager) }

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 隐私政策弹窗的结果（页面重建后对话框仍在，重新注册监听即可收到）
        supportFragmentManager.setFragmentResultListener(REQUEST_PRIVACY, this) { _, result ->
            if (result.getBoolean(MessageDialogFragment.RESULT_POSITIVE)) {
                log.info("用户同意隐私政策")
                viewModel.onPrivacyAgreed()
            } else {
                viewModel.onPrivacyRejected()
            }
        }
        collectWhenStarted(viewModel.events, ::onEvent)
        viewModel.start()
    }

    /**
     * 处理启动页事件。
     *
     * @param event 事件
     */
    private fun onEvent(event: SplashEvent) {
        when (event) {
            SplashEvent.ShowPrivacy -> MessageDialogFragment.show(
                fragmentManager = supportFragmentManager,
                requestKey = REQUEST_PRIVACY,
                title = R.string.privacy_title,
                message = R.string.privacy_content,
                positive = R.string.privacy_agree,
                negative = R.string.privacy_disagree,
            )
            SplashEvent.GoLogin -> startAsNewTask(LoginActivity::class.java)
            SplashEvent.GoMain -> startAsNewTask(MainActivity::class.java)
            SplashEvent.GoChangePassword -> startAsNewTask(ChangePasswordActivity::class.java)
            SplashEvent.Exit -> finish()
        }
    }

    private companion object {
        const val TAG = "SplashActivity"

        /** 隐私政策对话框的结果 key */
        const val REQUEST_PRIVACY = "privacy"
    }
}
