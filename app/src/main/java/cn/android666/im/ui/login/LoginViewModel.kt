package cn.android666.im.ui.login

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.data.account.CredentialStore
import cn.android666.im.data.session.SessionManager
import cn.android666.im.ui.common.ErrorMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录页状态。
 *
 * @property savedUsername 上次登录的工号，用于回显（只在初始化时填一次）
 * @property loading 是否正在登录
 * @property usernameError 工号输入框的错误提示
 * @property passwordError 密码输入框的错误提示
 * @property error 登录失败的提示
 */
data class LoginUiState(
    val savedUsername: String? = null,
    val loading: Boolean = false,
    @param:StringRes val usernameError: Int? = null,
    @param:StringRes val passwordError: Int? = null,
    @param:StringRes val error: Int? = null,
)

/** 登录页的一次性事件 */
sealed interface LoginEvent {
    /** 登录成功，进入主界面（需要改密时由主界面转到改密页） */
    data object GoMain : LoginEvent
}

/**
 * 登录页（开发计划 2.19）：工号 + 密码登录。
 *
 * @property sessionManager 登录编排
 * @property credentialStore 读取上次登录的工号
 */
class LoginViewModel(
    private val sessionManager: SessionManager,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    private val log = Logs.file(TAG)

    private val mutableState = MutableStateFlow(LoginUiState())

    /** 页面状态 */
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<LoginEvent>(Channel.BUFFERED)

    /** 一次性事件 */
    val events: Flow<LoginEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val saved = credentialStore.load()?.username
            mutableState.update { it.copy(savedUsername = saved) }
        }
    }

    /**
     * 登录。
     *
     * @param usernameInput 输入的工号（自动去空格、转小写，协议规范 §1.1：工号只含小写字母、数字、`.`、`_`、`-`）
     * @param password 输入的密码
     */
    fun login(usernameInput: String, password: String) {
        if (mutableState.value.loading) return
        val username = usernameInput.trim().lowercase()
        val usernameError = when {
            username.isEmpty() -> R.string.login_username_empty
            !USERNAME_PATTERN.matches(username) -> R.string.login_username_invalid
            else -> null
        }
        val passwordError = if (password.isEmpty()) R.string.login_password_empty else null
        mutableState.update { it.copy(usernameError = usernameError, passwordError = passwordError, error = null) }
        if (usernameError != null || passwordError != null) return

        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                sessionManager.login(Credential(username, password))
                eventChannel.send(LoginEvent.GoMain)
            } catch (e: XmppException) {
                log.warn("登录失败：{}", e.message)
                mutableState.update { it.copy(error = ErrorMessages.forException(e)) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    private companion object {
        const val TAG = "LoginViewModel"

        /** 工号允许的字符（协议规范 §1.1） */
        val USERNAME_PATTERN = Regex("^[a-z0-9._-]+$")
    }
}
