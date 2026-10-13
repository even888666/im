package cn.android666.im.ui.password

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.data.session.SessionManager
import cn.android666.im.ui.common.ErrorMessages
import cn.android666.im.ui.common.PasswordRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 改密页状态。
 *
 * @property loading 是否正在提交
 * @property error 提示文字（本地校验或服务器错误）
 */
data class ChangePasswordUiState(
    val loading: Boolean = false,
    @param:StringRes val error: Int? = null,
)

/** 改密页的一次性事件 */
sealed interface ChangePasswordEvent {
    /** 修改成功，进入主界面 */
    data object Done : ChangePasswordEvent

    /** 已退出登录，回到登录页 */
    data object LoggedOut : ChangePasswordEvent
}

/**
 * 强制修改初始密码（开发计划 2.20，协议规范 §3.1、§3.2）。
 *
 * @property sessionManager 登录编排（改密成功后继续执行暂停的登录步骤）
 */
class ChangePasswordViewModel(private val sessionManager: SessionManager) : ViewModel() {

    private val log = Logs.file(TAG)

    private val mutableState = MutableStateFlow(ChangePasswordUiState())

    /** 页面状态 */
    val state: StateFlow<ChangePasswordUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ChangePasswordEvent>(Channel.BUFFERED)

    /** 一次性事件 */
    val events: Flow<ChangePasswordEvent> = eventChannel.receiveAsFlow()

    /**
     * 提交新密码：先按规则本地校验，再请求服务器。
     *
     * @param password 新密码
     * @param confirm 再次输入的新密码
     */
    fun submit(password: String, confirm: String) {
        if (mutableState.value.loading) return
        val ruleError = PasswordRules.validate(password, confirm)
        if (ruleError != null) {
            mutableState.update { it.copy(error = ruleError) }
            return
        }
        mutableState.update { ChangePasswordUiState(loading = true) }
        viewModelScope.launch {
            try {
                sessionManager.changePassword(password)
                eventChannel.send(ChangePasswordEvent.Done)
            } catch (e: XmppException) {
                log.warn("修改密码失败：{}", e.message)
                mutableState.update { it.copy(error = ErrorMessages.forException(e)) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    /** 不改密码，直接退出登录 */
    fun logout() {
        viewModelScope.launch {
            sessionManager.logout()
            eventChannel.send(ChangePasswordEvent.LoggedOut)
        }
    }

    private companion object {
        const val TAG = "ChangePasswordViewModel"
    }
}
