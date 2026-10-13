package cn.android666.im.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.core.log.Logs
import cn.android666.im.data.session.SessionManager
import cn.android666.im.data.session.SessionState
import cn.android666.im.data.settings.AppSettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 启动页的一次性事件 */
sealed interface SplashEvent {
    /** 显示隐私政策弹窗 */
    data object ShowPrivacy : SplashEvent

    /** 进入登录页 */
    data object GoLogin : SplashEvent

    /** 进入主界面（自动登录在后台进行，主界面先显示本地数据） */
    data object GoMain : SplashEvent

    /** 进入强制改密页 */
    data object GoChangePassword : SplashEvent

    /** 用户不同意隐私政策，退出应用 */
    data object Exit : SplashEvent
}

/**
 * 启动页（开发计划 2.17、2.18、Android工程架构.md §6.3"冷启动"）：
 * 1. 未同意当前版本的隐私政策 → 弹窗，同意前不做任何联网和数据采集（功能清单 §14）；
 * 2. 已登录（进程还活着）→ 按会话状态进入主界面或改密页；
 * 3. 有保存的凭证 → 开始自动登录并进入主界面；否则进入登录页。
 *
 * @property settings 设备级设置
 * @property sessionManager 登录编排
 */
class SplashViewModel(
    private val settings: AppSettingsStore,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val log = Logs.file(TAG)

    private val eventChannel = Channel<SplashEvent>(Channel.BUFFERED)

    /** 一次性事件（Channel 保证旋转屏幕后不会重复触发） */
    val events: Flow<SplashEvent> = eventChannel.receiveAsFlow()

    /** 是否已经开始处理，避免页面重建时重复执行 */
    private var started = false

    /** 页面创建后调用，开始判断去向 */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            if (settings.isPrivacyAgreed(PRIVACY_VERSION)) {
                route()
            } else {
                log.info("尚未同意隐私政策（版本 {}），弹窗", PRIVACY_VERSION)
                eventChannel.send(SplashEvent.ShowPrivacy)
            }
        }
    }

    /** 用户同意隐私政策 */
    fun onPrivacyAgreed() {
        viewModelScope.launch {
            settings.setPrivacyAgreed(PRIVACY_VERSION)
            route()
        }
    }

    /** 用户不同意隐私政策：退出应用 */
    fun onPrivacyRejected() {
        log.info("用户不同意隐私政策，退出")
        eventChannel.trySend(SplashEvent.Exit)
    }

    /** 根据会话状态决定去向 */
    private suspend fun route() {
        val event = when (sessionManager.state.value) {
            is SessionState.Ready, is SessionState.Starting -> SplashEvent.GoMain
            is SessionState.MustChangePassword -> SplashEvent.GoChangePassword
            // 会话被结束（被挤下线等）后不自动登录，回到登录页
            is SessionState.Ended -> SplashEvent.GoLogin
            SessionState.LoggedOut -> if (sessionManager.startAutoLogin()) SplashEvent.GoMain else SplashEvent.GoLogin
        }
        log.info("启动去向：{}", event)
        eventChannel.send(event)
    }

    companion object {
        private const val TAG = "Splash"

        /** 当前隐私政策版本：隐私政策内容更新时加 1，所有用户需要重新同意 */
        const val PRIVACY_VERSION = 1
    }
}
