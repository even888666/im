package cn.android666.im.core.xmpp

import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** XMPP 模型与 [FakeXmppClient] 的行为测试 */
class XmppModelTest {

    /** 被挤下线、被禁用、认证失败不重连；网络原因和其他 stream error 重连 */
    @Test
    fun disconnectReason_shouldReconnect() {
        assertThat(DisconnectReason.Network(null).shouldReconnect).isTrue()
        assertThat(DisconnectReason.StreamError("system-shutdown").shouldReconnect).isTrue()
        assertThat(DisconnectReason.Conflict.shouldReconnect).isFalse()
        assertThat(DisconnectReason.AccountDisabled.shouldReconnect).isFalse()
        assertThat(DisconnectReason.AuthenticationFailed.shouldReconnect).isFalse()
    }

    /** 凭证的 toString 不包含密码，防止被写进日志 */
    @Test
    fun credential_toString_hidesPassword() {
        val text = Credential("10001", "secret123").toString()
        assertThat(text).contains("10001")
        assertThat(text).doesNotContain("secret123")
    }

    @Test
    fun stanzaError_messageContainsConditionAndCode() {
        val e = XmppException.StanzaError("not-acceptable", "weak-password")
        assertThat(e.message).contains("not-acceptable")
        assertThat(e.message).contains("weak-password")
        assertThat(XmppException.StanzaError("forbidden", null).appErrorCode).isNull()
    }

    @Test
    fun fake_loginSuccess_stateAuthenticated() = runTest {
        val client = FakeXmppClient()

        val result = client.login(Credential("10001", "p"))

        assertThat(result.resumed).isFalse()
        assertThat(client.connectionState.value).isEqualTo(ConnectionState.Authenticated(resumed = false))
    }

    @Test
    fun fake_loginFailure_throwsAndStateDisconnected() = runTest {
        val client = FakeXmppClient()
        client.loginResults += XmppException.AuthenticationFailed()

        val e = runCatching { client.login(Credential("10001", "wrong")) }.exceptionOrNull()

        assertThat(e).isInstanceOf(XmppException.AuthenticationFailed::class.java)
        assertThat(client.connectionState.value)
            .isEqualTo(ConnectionState.Disconnected(DisconnectReason.AuthenticationFailed))
    }

    @Test
    fun fake_reconnectWithoutLogin_throwsNotConnected() = runTest {
        val client = FakeXmppClient()

        val e = runCatching { client.reconnect() }.exceptionOrNull()

        assertThat(e).isInstanceOf(XmppException.NotConnected::class.java)
    }

    @Test
    fun fake_simulateDisconnectThenReconnect() = runTest {
        val client = FakeXmppClient()
        client.login(Credential("10001", "p"))

        client.simulateDisconnect(DisconnectReason.Network(null))
        assertThat(client.connectionState.value).isInstanceOf(ConnectionState.Disconnected::class.java)

        val result = client.reconnect()
        assertThat(result.resumed).isTrue()
        assertThat(client.calls).containsExactly("login:10001", "reconnect").inOrder()
    }
}
