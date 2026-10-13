package cn.android666.im.ui.login

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cn.android666.im.R
import cn.android666.im.databinding.ActivityLoginBinding
import cn.android666.im.ui.base.appContainer
import cn.android666.im.ui.base.appViewModels
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.base.startAsNewTask
import cn.android666.im.ui.main.MainActivity

/**
 * 登录页（开发计划 2.19）：输入工号和密码登录，成功后进入主界面（需要改密时由主界面转到改密页）。
 */
class LoginActivity : AppCompatActivity() {

    private val viewModel by appViewModels { LoginViewModel(it.sessionManager, it.credentialStore) }

    private lateinit var binding: ActivityLoginBinding

    /** 是否已经处理过上次登录工号的回显（只回显一次，用户清空输入框后不再填回） */
    private var savedUsernameHandled = false

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsPadding(binding.root)

        binding.serverText.text = getString(R.string.login_server, appContainer.serverConfig.domain)
        binding.loginButton.setOnClickListener { submit() }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        collectWhenStarted(viewModel.state, ::render)
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                LoginEvent.GoMain -> startAsNewTask(MainActivity::class.java)
            }
        }
    }

    /** 点击登录 */
    private fun submit() {
        viewModel.login(binding.usernameInput.text?.toString().orEmpty(), binding.passwordInput.text?.toString().orEmpty())
    }

    /**
     * 根据状态刷新界面。
     *
     * @param state 登录页状态
     */
    private fun render(state: LoginUiState) {
        // 回显上次登录的工号：只回显一次，且只在输入框为空时填入（页面重建时输入框会自己恢复内容）
        val saved = state.savedUsername
        if (saved != null && !savedUsernameHandled) {
            savedUsernameHandled = true
            if (binding.usernameInput.text.isNullOrEmpty()) {
                binding.usernameInput.setText(saved)
                binding.passwordInput.requestFocus()
            }
        }
        binding.usernameLayout.error = state.usernameError?.let(::getString)
        binding.passwordLayout.error = state.passwordError?.let(::getString)
        binding.errorText.isVisible = state.error != null
        state.error?.let(binding.errorText::setText)
        binding.progress.isVisible = state.loading
        binding.loginButton.visibility = if (state.loading) View.INVISIBLE else View.VISIBLE
        binding.usernameInput.isEnabled = !state.loading
        binding.passwordInput.isEnabled = !state.loading
    }
}
