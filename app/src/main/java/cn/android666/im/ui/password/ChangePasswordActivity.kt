package cn.android666.im.ui.password

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cn.android666.im.R
import cn.android666.im.databinding.ActivityChangePasswordBinding
import cn.android666.im.ui.base.appViewModels
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.base.startAsNewTask
import cn.android666.im.ui.login.LoginActivity
import cn.android666.im.ui.main.MainActivity

/**
 * 强制修改初始密码页（开发计划 2.20）：改密成功前不能使用其他功能；也可以选择退出登录。
 */
class ChangePasswordActivity : AppCompatActivity() {

    private val viewModel by appViewModels { ChangePasswordViewModel(it.sessionManager) }

    private lateinit var binding: ActivityChangePasswordBinding

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChangePasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsPadding(binding.root)

        binding.submitButton.setOnClickListener { submit() }
        binding.confirmInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.logoutButton.setOnClickListener { viewModel.logout() }
        collectWhenStarted(viewModel.state, ::render)
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                ChangePasswordEvent.Done -> {
                    Toast.makeText(this, R.string.change_password_success, Toast.LENGTH_SHORT).show()
                    startAsNewTask(MainActivity::class.java)
                }
                ChangePasswordEvent.LoggedOut -> startAsNewTask(LoginActivity::class.java)
            }
        }
    }

    /** 点击确认修改 */
    private fun submit() {
        viewModel.submit(binding.passwordInput.text?.toString().orEmpty(), binding.confirmInput.text?.toString().orEmpty())
    }

    /**
     * 根据状态刷新界面。
     *
     * @param state 改密页状态
     */
    private fun render(state: ChangePasswordUiState) {
        binding.errorText.isVisible = state.error != null
        state.error?.let(binding.errorText::setText)
        binding.progress.isVisible = state.loading
        binding.submitButton.visibility = if (state.loading) View.INVISIBLE else View.VISIBLE
        binding.passwordInput.isEnabled = !state.loading
        binding.confirmInput.isEnabled = !state.loading
        binding.logoutButton.isEnabled = !state.loading
    }
}
