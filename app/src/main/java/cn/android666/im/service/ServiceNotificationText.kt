package cn.android666.im.service

import androidx.annotation.StringRes
import cn.android666.im.R
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.DisconnectReason

/**
 * 前台服务常驻通知的文字（连接状态），纯逻辑，便于单元测试。
 */
object ServiceNotificationText {

    /**
     * 连接状态 → 通知文字的字符串资源。
     *
     * @param state 连接状态
     * @return 字符串资源 ID
     */
    @StringRes
    fun forState(state: ConnectionState): Int = when (state) {
        is ConnectionState.Authenticated -> R.string.service_status_connected
        ConnectionState.Connecting -> R.string.service_status_connecting
        ConnectionState.Idle -> R.string.service_status_idle
        is ConnectionState.Disconnected -> when (state.reason) {
            DisconnectReason.Conflict -> R.string.service_status_conflict
            DisconnectReason.AccountDisabled -> R.string.service_status_disabled
            DisconnectReason.AuthenticationFailed -> R.string.service_status_auth_failed
            else -> R.string.service_status_disconnected
        }
    }
}
