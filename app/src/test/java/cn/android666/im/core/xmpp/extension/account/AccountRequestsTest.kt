package cn.android666.im.core.xmpp.extension.account

import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.XmlNode
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** 账号相关 IQ 测试（协议规范 §3.1、§3.2） */
class AccountRequestsTest {

    /** 请求与协议规范 §3.1 示例一致 */
    @Test
    fun accountStatus_requestMatchesProtocol() {
        val request = AccountStatusRequest()

        assertThat(request.type).isEqualTo(IqType.GET)
        assertThat(request.to).isNull() // 发往服务器域名
        assertThat(request.toChildXml()).isEqualTo("<account xmlns='urn:android666:im:account:1'/>")
    }

    /** 解析协议规范 §3.1 的结果示例 */
    @Test
    fun accountStatus_parseProtocolResult() {
        val result = XmlNode.parse(
            """
            <account xmlns='urn:android666:im:account:1'
                     mustChangePassword='true'/>
            """.trimIndent(),
        )

        assertThat(AccountStatusRequest().parseResult(result)).isEqualTo(AccountStatus(mustChangePassword = true))
    }

    @Test
    fun accountStatus_attributeMissing_noNeedToChange() {
        val result = XmlNode.parse("<account xmlns='urn:android666:im:account:1'/>")
        assertThat(AccountStatusRequest().parseResult(result).mustChangePassword).isFalse()
    }

    @Test
    fun accountStatus_wrongResult_throws() {
        assertThrows(IllegalArgumentException::class.java) { AccountStatusRequest().parseResult(null) }
        assertThrows(IllegalArgumentException::class.java) {
            AccountStatusRequest().parseResult(XmlNode.parse("<account xmlns='urn:other'/>"))
        }
    }

    /** 请求与协议规范 §3.2 示例一致，特殊字符被转义 */
    @Test
    fun changePassword_requestMatchesProtocol() {
        val request = ChangePasswordRequest("10086", "新密码")

        assertThat(request.type).isEqualTo(IqType.SET)
        assertThat(request.toChildXml()).isEqualTo(
            "<query xmlns='jabber:iq:register'><username>10086</username><password>新密码</password></query>",
        )
        assertThat(ChangePasswordRequest("10086", "a<b&c").childrenXml()).contains("<password>a&lt;b&amp;c</password>")
    }

    @Test
    fun changePassword_toStringHidesPassword() {
        assertThat(ChangePasswordRequest("10086", "secret1").toString()).doesNotContain("secret1")
    }

    @Test
    fun changePassword_emptyResultAccepted() {
        ChangePasswordRequest("10086", "x").parseResult(null)
    }
}
