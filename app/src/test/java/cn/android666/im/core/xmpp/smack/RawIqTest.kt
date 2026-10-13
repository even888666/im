package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.extension.account.AccountStatusRequest
import cn.android666.im.core.xmpp.extension.account.ChangePasswordRequest
import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.XmlNode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [RawIq] 测试：生成的报文与协议一致 */
class RawIqTest {

    @Test
    fun emptyChild_getIq() {
        val xml = RawIq(AccountStatusRequest()).apply { stanzaId = "a1" }.toXML().toString()

        assertThat(xml).contains("type='get'")
        assertThat(xml).contains("<account xmlns='urn:android666:im:account:1'/>")
    }

    @Test
    fun childrenXml_setIq() {
        val xml = RawIq(ChangePasswordRequest("10086", "abc")).toXML().toString()

        assertThat(xml).contains("type='set'")
        assertThat(xml).contains("<query xmlns='jabber:iq:register'><username>10086</username><password>abc</password></query>")
    }

    /** 属性值由 Smack 转义 */
    @Test
    fun attributes_escaped() {
        val request = object : IqRequest<Unit>(IqType.GET, "search", "urn:android666:im:org:1") {
            override val attributes = mapOf("keyword" to "a'b<c")
            override fun parseResult(result: XmlNode?) = Unit
        }

        val xml = RawIq(request).toXML().toString()

        assertThat(xml).contains("keyword='a&apos;b&lt;c'")
    }
}
