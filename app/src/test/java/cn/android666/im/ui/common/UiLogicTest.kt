package cn.android666.im.ui.common

import cn.android666.im.R
import cn.android666.im.core.network.NetworkStatus
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.ui.main.ConnectionBanner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 界面纯逻辑测试：错误文案、密码规则、连接状态条 */
class UiLogicTest {

    @Test
    fun errorMessages_exceptions() {
        assertThat(ErrorMessages.forException(XmppException.AuthenticationFailed())).isEqualTo(R.string.error_auth_failed)
        assertThat(ErrorMessages.forException(XmppException.ConnectionFailed("x"))).isEqualTo(R.string.error_connection_failed)
        assertThat(ErrorMessages.forException(XmppException.NotConnected())).isEqualTo(R.string.error_connection_failed)
        assertThat(ErrorMessages.forException(XmppException.Timeout())).isEqualTo(R.string.error_timeout)
        assertThat(ErrorMessages.forException(XmppException.TlsFailed())).isEqualTo(R.string.error_tls_failed)
        assertThat(ErrorMessages.forException(XmppException.InvalidResponse("x"))).isEqualTo(R.string.error_invalid_response)
        assertThat(ErrorMessages.forException(IllegalStateException())).isEqualTo(R.string.error_unknown)
    }

    /** 协议规范 §16 错误码表 */
    @Test
    fun errorMessages_appErrorCodes() {
        assertThat(ErrorMessages.forException(XmppException.StanzaError("not-acceptable", "weak-password")))
            .isEqualTo(R.string.error_weak_password)
        assertThat(ErrorMessages.forAppErrorCode("sensitive-word")).isEqualTo(R.string.error_sensitive_word)
        assertThat(ErrorMessages.forAppErrorCode("retract-timeout")).isEqualTo(R.string.error_retract_timeout)
        assertThat(ErrorMessages.forAppErrorCode("room-full")).isEqualTo(R.string.error_room_full)
        assertThat(ErrorMessages.forAppErrorCode("must-change-password")).isEqualTo(R.string.error_must_change_password)
        assertThat(ErrorMessages.forAppErrorCode("future-code")).isEqualTo(R.string.error_server)
        assertThat(ErrorMessages.forAppErrorCode(null)).isEqualTo(R.string.error_server)
    }

    @Test
    fun errorMessages_sessionEnded() {
        assertThat(ErrorMessages.forSessionEnded(DisconnectReason.Conflict)).isEqualTo(R.string.error_conflict)
        assertThat(ErrorMessages.forSessionEnded(DisconnectReason.AccountDisabled)).isEqualTo(R.string.error_account_disabled)
        assertThat(ErrorMessages.forSessionEnded(DisconnectReason.AuthenticationFailed)).isEqualTo(R.string.error_session_auth_failed)
    }

    /** 协议规范 §3.2：8 ~ 32 位，至少包含字母和数字 */
    @Test
    fun passwordRules() {
        assertThat(PasswordRules.validate("abc12345", "abc12345")).isNull()
        assertThat(PasswordRules.validate("Ab1" + "x".repeat(29), "Ab1" + "x".repeat(29))).isNull() // 32 位
        assertThat(PasswordRules.validate("abc1234", "abc1234")).isEqualTo(R.string.password_rule_length)
        assertThat(PasswordRules.validate("a1" + "x".repeat(31), "a1" + "x".repeat(31))).isEqualTo(R.string.password_rule_length)
        assertThat(PasswordRules.validate("abcdefgh", "abcdefgh")).isEqualTo(R.string.password_rule_letter_digit)
        assertThat(PasswordRules.validate("12345678", "12345678")).isEqualTo(R.string.password_rule_letter_digit)
        assertThat(PasswordRules.validate("中文密码12345", "中文密码12345")).isEqualTo(R.string.password_rule_letter_digit)
        assertThat(PasswordRules.validate("abc 12345", "abc 12345")).isEqualTo(R.string.password_rule_no_space)
        assertThat(PasswordRules.validate("abc12345", "abc12346")).isEqualTo(R.string.password_rule_mismatch)
    }

    @Test
    fun connectionBanner() {
        val online = NetworkStatus(available = true, networkId = "wifi")
        assertThat(ConnectionBanner.textFor(ConnectionState.Authenticated(false), online)).isNull()
        // 已连接时即使网络状态显示不可用也不显示（以实际连接为准）
        assertThat(ConnectionBanner.textFor(ConnectionState.Authenticated(true), NetworkStatus.NONE)).isNull()
        assertThat(ConnectionBanner.textFor(ConnectionState.Connecting, online)).isEqualTo(R.string.banner_connecting)
        assertThat(ConnectionBanner.textFor(ConnectionState.Disconnected(DisconnectReason.Network(null)), online))
            .isEqualTo(R.string.banner_disconnected)
        assertThat(ConnectionBanner.textFor(ConnectionState.Disconnected(DisconnectReason.Network(null)), NetworkStatus.NONE))
            .isEqualTo(R.string.banner_no_network)
        assertThat(ConnectionBanner.textFor(ConnectionState.Connecting, NetworkStatus.NONE)).isEqualTo(R.string.banner_no_network)
        // 已连接、正在补拉消息：显示"收取中…"；未连接时补拉标志不影响
        assertThat(ConnectionBanner.textFor(ConnectionState.Authenticated(false), online, syncing = true)).isEqualTo(R.string.banner_syncing)
        assertThat(ConnectionBanner.textFor(ConnectionState.Connecting, online, syncing = true)).isEqualTo(R.string.banner_connecting)
    }
}
