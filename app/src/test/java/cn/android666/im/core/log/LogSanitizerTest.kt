package cn.android666.im.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [LogSanitizer] 脱敏规则测试 */
class LogSanitizerTest {

    @Test
    fun saslAuth_contentMasked() {
        val raw = "<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='SCRAM-SHA-1'>biwsbj0xMDA4NixyPWZ5a28=</auth>"
        assertThat(LogSanitizer.sanitizeStanza(raw))
            .isEqualTo("<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='SCRAM-SHA-1'>***</auth>")
    }

    @Test
    fun saslChallengeResponseSuccess_contentMasked() {
        val raw = "<challenge xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>cj1meWtv</challenge>" +
            "<response xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>Yz1iaXdz</response>" +
            "<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>dj1ybUY5</success>"
        val sanitized = LogSanitizer.sanitizeStanza(raw)

        assertThat(sanitized).doesNotContain("cj1meWtv")
        assertThat(sanitized).doesNotContain("Yz1iaXdz")
        assertThat(sanitized).doesNotContain("dj1ybUY5")
        assertThat(sanitized).contains("<response xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>***</response>")
    }

    /** 空的 success（无附加数据）保持不变 */
    @Test
    fun emptySuccess_unchanged() {
        val raw = "<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>"
        assertThat(LogSanitizer.sanitizeStanza(raw)).isEqualTo(raw)
    }

    /** 协议规范 §3.2 改密报文 */
    @Test
    fun changePassword_passwordMasked() {
        val raw = "<iq type='set' id='a2' to='im.example.com'><query xmlns='jabber:iq:register'>" +
            "<username>10086</username><password>新密码abc123</password></query></iq>"
        val sanitized = LogSanitizer.sanitizeStanza(raw)

        assertThat(sanitized).contains("<username>10086</username><password>***</password>")
        assertThat(sanitized).doesNotContain("新密码abc123")
    }

    /** 协议规范 §12.1 TURN 凭证中的 password 属性，单双引号都处理 */
    @Test
    fun turnPasswordAttribute_masked() {
        val raw = "<service type='turn' host='turn.im.example.com' username='1760342400:10086' password='s3cr3t/+='/>" +
            "<service type=\"turn\" password=\"abc\"/>"
        val sanitized = LogSanitizer.sanitizeStanza(raw)

        assertThat(sanitized).contains("username='1760342400:10086' password='***'")
        assertThat(sanitized).contains("password=\"***\"")
        assertThat(sanitized).doesNotContain("s3cr3t")
    }

    /** 普通消息不受影响（包括正文中出现 password 字样） */
    @Test
    fun normalMessage_unchanged() {
        val raw = "<message type='chat' id='5b1e'><body>我的 password 忘了，auth 失败</body></message>"
        assertThat(LogSanitizer.sanitizeStanza(raw)).isEqualTo(raw)
    }

    @Test
    fun maskSecret_keepsOnlyLength() {
        assertThat(LogSanitizer.maskSecret("abc12345")).isEqualTo("***(len=8)")
        assertThat(LogSanitizer.maskSecret("")).isEqualTo("***(len=0)")
        assertThat(LogSanitizer.maskSecret(null)).isEqualTo("null")
    }
}
