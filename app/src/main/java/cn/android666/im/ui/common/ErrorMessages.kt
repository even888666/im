package cn.android666.im.ui.common

import androidx.annotation.StringRes
import cn.android666.im.R
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppException

/**
 * 把错误转换为给用户看的提示文字（协议规范 §2.4、§16 中的"客户端提示"）。纯逻辑，便于单元测试。
 */
object ErrorMessages {

    /**
     * 登录 / 请求失败 → 提示文字。
     *
     * @param error 异常
     * @return 字符串资源 ID
     */
    @StringRes
    fun forException(error: Throwable): Int = when (error) {
        is XmppException.AuthenticationFailed -> R.string.error_auth_failed
        is XmppException.TlsFailed -> R.string.error_tls_failed
        is XmppException.Timeout -> R.string.error_timeout
        is XmppException.ConnectionFailed, is XmppException.NotConnected -> R.string.error_connection_failed
        is XmppException.Conflict -> R.string.error_conflict
        is XmppException.AccountDisabled -> R.string.error_account_disabled
        is XmppException.StanzaError -> forAppErrorCode(error.appErrorCode)
        is XmppException.InvalidResponse -> R.string.error_invalid_response
        else -> R.string.error_unknown
    }

    /**
     * 自定义错误码（协议规范 §16）→ 提示文字。
     *
     * @param code 错误码，可为 null
     * @return 字符串资源 ID；未知错误码返回通用提示
     */
    @StringRes
    fun forAppErrorCode(code: String?): Int = when (code) {
        "must-change-password" -> R.string.error_must_change_password
        "weak-password" -> R.string.error_weak_password
        "sensitive-word" -> R.string.error_sensitive_word
        "retract-timeout" -> R.string.error_retract_timeout
        "retract-forbidden" -> R.string.error_retract_forbidden
        "mention-all-forbidden" -> R.string.error_mention_all_forbidden
        "room-full" -> R.string.error_room_full
        "not-member" -> R.string.error_not_member
        else -> R.string.error_server
    }

    /**
     * 会话被结束的原因 → 提示文字（被挤下线、禁用等，协议规范 §2.4）。
     *
     * @param reason 原因
     * @return 字符串资源 ID
     */
    @StringRes
    fun forSessionEnded(reason: DisconnectReason): Int = when (reason) {
        DisconnectReason.Conflict -> R.string.error_conflict
        DisconnectReason.AccountDisabled -> R.string.error_account_disabled
        DisconnectReason.AuthenticationFailed -> R.string.error_session_auth_failed
        else -> R.string.error_connection_failed
    }
}
