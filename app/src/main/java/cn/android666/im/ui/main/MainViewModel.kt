package cn.android666.im.ui.main

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.core.network.NetworkStatus
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.data.session.SessionManager
import cn.android666.im.data.session.SessionState
import cn.android666.im.ui.common.ErrorMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 主界面的一次性事件 */
sealed interface MainEvent {
    /** 需要强制改密，进入改密页 */
    data object GoChangePassword : MainEvent

    /**
     * 会话被结束（被挤下线、禁用、认证失败），弹窗提示后回到登录页（开发计划 2.22）。
     *
     * @property message 提示文字
     */
    data class SessionEnded(@param:StringRes val message: Int) : MainEvent

    /** 已退出登录（或会话已不存在），回到登录页 */
    data object GoLogin : MainEvent
}

/**
 * 主界面（开发计划 2.21 ~ 2.23）：底部 Tab、顶部连接状态条、会话被结束的提示、退出登录。
 *
 * @property sessionManager 登录编排
 * @param connectionState 连接状态
 * @param networkStatus 网络状态
 * @param totalUnread 总未读数（"消息" Tab 角标）
 * @param syncing 是否正在补拉消息（状态条显示"收取中…"）
 */
class MainViewModel(
    private val sessionManager: SessionManager,
    connectionState: StateFlow<ConnectionState>,
    networkStatus: StateFlow<NetworkStatus>,
    totalUnread: Flow<Int> = flowOf(0),
    syncing: StateFlow<Boolean> = MutableStateFlow(false),
) : ViewModel() {

    /** 总未读数（"消息" Tab 角标），0 时不显示 */
    val totalUnread: StateFlow<Int> = totalUnread.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    /** 顶部连接状态条的文字；null 表示已连接、不显示 */
    @get:StringRes
    val banner: StateFlow<Int?> = combine(connectionState, networkStatus, syncing, ConnectionBanner::textFor)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ConnectionBanner.textFor(connectionState.value, networkStatus.value, syncing.value),
        )

    /** 当前登录的工号（"我"页面显示） */
    val username: StateFlow<String?> = sessionManager.state.map { it.username() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sessionManager.state.value.username())

    private val eventChannel = Channel<MainEvent>(Channel.BUFFERED)

    /** 一次性事件 */
    val events: Flow<MainEvent> = eventChannel.receiveAsFlow()

    init {
        // 会话状态变化 → 页面跳转（改密、被挤下线、已退出）
        viewModelScope.launch {
            sessionManager.state.collect { state ->
                when (state) {
                    is SessionState.MustChangePassword -> eventChannel.send(MainEvent.GoChangePassword)
                    is SessionState.Ended -> eventChannel.send(MainEvent.SessionEnded(ErrorMessages.forSessionEnded(state.reason)))
                    SessionState.LoggedOut -> eventChannel.send(MainEvent.GoLogin)
                    is SessionState.Starting, is SessionState.Ready -> Unit
                }
            }
        }
    }

    /** 退出登录（开发计划 2.23）：完成后通过会话状态变为 LoggedOut 触发回到登录页 */
    fun logout() {
        viewModelScope.launch { sessionManager.logout() }
    }

    /**
     * 会话状态中的工号。
     *
     * @return 工号；未登录时为 null
     */
    private fun SessionState.username(): String? = when (this) {
        is SessionState.Starting -> username
        is SessionState.Ready -> username
        is SessionState.MustChangePassword -> username
        is SessionState.Ended -> username
        SessionState.LoggedOut -> null
    }

    private companion object {
        /** 页面不可见超过该时间后停止查询未读数 */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
