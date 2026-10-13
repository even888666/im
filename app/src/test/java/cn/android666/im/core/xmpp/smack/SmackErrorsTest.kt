package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppException
import com.google.common.truth.Truth.assertThat
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.filter.StanzaTypeFilter
import org.jivesoftware.smack.packet.StreamError
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.sasl.packet.SaslNonza
import org.jivesoftware.smack.util.PacketParserUtils
import org.junit.Test
import org.jxmpp.JxmppContext
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/** [SmackErrors] 异常映射测试，含协议规范 §2.4 的 stream error 示例解析（开发计划 2.8） */
class SmackErrorsTest {

    /**
     * 按协议规范的写法解析一个 stream error。
     *
     * @param xml `<stream:error>` 元素
     * @return Smack 的 StreamErrorException
     */
    private fun streamError(xml: String): XMPPException.StreamErrorException {
        val parser = PacketParserUtils.getParserFor(xml)
        return XMPPException.StreamErrorException(
            PacketParserUtils.parseStreamError(parser, XmlEnvironment.EMPTY, JxmppContext.getDefaultContext()),
        )
    }

    /** 协议规范 §2.4：同一端在其他设备登录 */
    @Test
    fun conflict_mappedToConflict() {
        val e = streamError(
            "<stream:error xmlns:stream='http://etherx.jabber.org/streams'>" +
                "<conflict xmlns='urn:ietf:params:xml:ns:xmpp-streams'/></stream:error>",
        )

        assertThat(SmackErrors.toDisconnectReason(e)).isEqualTo(DisconnectReason.Conflict)
        assertThat(SmackErrors.toXmppException(e)).isInstanceOf(XmppException.Conflict::class.java)
    }

    /** 协议规范 §2.4 的原始示例：policy-violation + account-disabled */
    @Test
    fun accountDisabled_protocolExample_mappedToAccountDisabled() {
        val e = streamError(
            """
            <stream:error xmlns:stream='http://etherx.jabber.org/streams'>
              <policy-violation xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>
              <account-disabled xmlns='urn:android666:im:error:1'/>
            </stream:error>
            """.trimIndent(),
        )

        assertThat(SmackErrors.toDisconnectReason(e)).isEqualTo(DisconnectReason.AccountDisabled)
        assertThat(SmackErrors.toXmppException(e)).isInstanceOf(XmppException.AccountDisabled::class.java)
    }

    /** 只有 policy-violation、没有自定义元素时不是"账号被禁用" */
    @Test
    fun policyViolationWithoutExtension_isGenericStreamError() {
        val e = streamError(
            "<stream:error xmlns:stream='http://etherx.jabber.org/streams'>" +
                "<policy-violation xmlns='urn:ietf:params:xml:ns:xmpp-streams'/></stream:error>",
        )

        val reason = SmackErrors.toDisconnectReason(e)
        assertThat(reason).isInstanceOf(DisconnectReason.StreamError::class.java)
        assertThat((reason as DisconnectReason.StreamError).condition).isEqualTo("policy-violation")
        assertThat(reason.shouldReconnect).isTrue()
    }

    /** 未知子元素不影响解析（协议规范 §1.5） */
    @Test
    fun systemShutdownWithUnknownExtension_isGenericStreamError() {
        val e = streamError(
            "<stream:error xmlns:stream='http://etherx.jabber.org/streams'>" +
                "<system-shutdown xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
                "<unknown-ext xmlns='urn:example:future'/></stream:error>",
        )

        assertThat(SmackErrors.toDisconnectReason(e)).isEqualTo(DisconnectReason.StreamError("system-shutdown"))
    }

    @Test
    fun ioException_isNetworkDisconnect() {
        assertThat(SmackErrors.toDisconnectReason(IOException("reset")))
            .isInstanceOf(DisconnectReason.Network::class.java)
    }

    @Test
    fun saslFailure_isAuthenticationFailed() {
        val e = SASLErrorException("SCRAM-SHA-1", SaslNonza.SASLFailure("not-authorized"))

        assertThat(SmackErrors.toXmppException(e)).isInstanceOf(XmppException.AuthenticationFailed::class.java)
    }

    /** 证书不受信任：SSL 异常被包在其他异常里时也能识别 */
    @Test
    fun sslErrorInCauseChain_isTlsFailed() {
        val ssl = SSLHandshakeException("PKIX path building failed")
        val wrapped = IOException("连接失败", ssl)

        assertThat(SmackErrors.toXmppException(wrapped)).isInstanceOf(XmppException.TlsFailed::class.java)
        assertThat(SmackErrors.toXmppException(IOException(CertificateException("bad"))))
            .isInstanceOf(XmppException.TlsFailed::class.java)
    }

    @Test
    fun noResponse_isTimeout() {
        val e = SmackException.NoResponseException.newWith(5_000L, StanzaTypeFilter.IQ, false)

        assertThat(SmackErrors.toXmppException(e)).isInstanceOf(XmppException.Timeout::class.java)
    }

    @Test
    fun plainIoException_isConnectionFailed() {
        assertThat(SmackErrors.toXmppException(IOException("Connection refused")))
            .isInstanceOf(XmppException.ConnectionFailed::class.java)
    }

    @Test
    fun xmppException_returnedAsIs() {
        val original = XmppException.Timeout()
        assertThat(SmackErrors.toXmppException(original)).isSameInstanceAs(original)
    }

    @Test
    fun unknownException_isConnectionFailed() {
        assertThat(SmackErrors.toXmppException(IllegalStateException("x")))
            .isInstanceOf(XmppException.ConnectionFailed::class.java)
    }

    /** StreamError 条件枚举名与协议写法的转换（下划线 ↔ 横线） */
    @Test
    fun conditionToString_usesProtocolSpelling() {
        assertThat(StreamError.Condition.policy_violation.toString()).isEqualTo("policy-violation")
    }
}
