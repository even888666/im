package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.extension.account.AccountStatusRequest
import cn.android666.im.core.xmpp.extension.account.ChangePasswordRequest
import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.XmlNode
import com.google.common.truth.Truth.assertThat
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.UnparsedIQ
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.junit.Test
import org.jxmpp.JxmppContext

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

    /**
     * 结果解析：Smack 把没有解析器的结果解析为 UnparsedIQ，必须从 content 取原始子元素，属性才不会丢失
     * （踩坑 4.4.13：childElementXML 会转义原始内容并丢掉属性）。
     */
    @Test
    fun resultChildXml_unparsedIq_keepsAttributes() {
        val iq = parseIq(
            "<iq type='result' id='o3' from='im.example.com'>" +
                "<sync xmlns='urn:android666:im:org:1' version='1536' full='false'>" +
                "<employee jid='10050@im.example.com' op='delete'/>" +
                "<set xmlns='http://jabber.org/protocol/rsm'><first>d:d1</first><last>e:1</last><count>2</count></set>" +
                "</sync></iq>",
        )
        assertThat(iq).isInstanceOf(UnparsedIQ::class.java)

        val node = XmlNode.parse(resultChildXml(iq)!!)
        assertThat(node.name).isEqualTo("sync")
        assertThat(node.namespace).isEqualTo("urn:android666:im:org:1")
        assertThat(node.attr("version")).isEqualTo("1536")
        assertThat(node.children.first().attr("op")).isEqualTo("delete")
    }

    /**
     * 注册 [RawResultIqProvider] 后，嵌套元素的命名空间也保留（UnparsedIQ 会把 RSM 的 set 变成没有 xmlns 的 set）。
     */
    @Test
    fun rawResultProvider_keepsNestedNamespaces() {
        val request = object : IqRequest<Unit>(IqType.GET, "sync", "urn:android666:im:org:1") {
            override fun parseResult(result: XmlNode?) = Unit
        }
        RawResultIqProvider.registerFor(request)

        val iq = parseIq(
            "<iq type='result' id='o3' from='im.example.com'>" +
                "<sync xmlns='urn:android666:im:org:1' version='1536' full='false'>" +
                "<set xmlns='http://jabber.org/protocol/rsm'><first>d:d1</first><last>e:1</last><count>2</count></set>" +
                "</sync></iq>",
        )
        assertThat(iq).isInstanceOf(RawResultIq::class.java)
        val node = XmlNode.parse(resultChildXml(iq)!!)
        assertThat(node.attr("version")).isEqualTo("1536")
        assertThat(node.child("set", "http://jabber.org/protocol/rsm")?.child("last")?.text).isEqualTo("e:1")
    }

    /** Smack 已有解析器的元素（XEP-0077 query）不覆盖 */
    @Test
    fun rawResultProvider_doesNotOverrideSmackProviders() {
        val before = ProviderManager.getIQProvider("query", "jabber:iq:register")
        RawResultIqProvider.registerFor(ChangePasswordRequest("10001", "x"))
        assertThat(ProviderManager.getIQProvider("query", "jabber:iq:register")).isSameInstanceAs(before)
    }

    /** 空结果没有子元素 */
    @Test
    fun resultChildXml_emptyResult_null() {
        val iq = parseIq("<iq type='result' id='a2' from='im.example.com'/>")
        assertThat(resultChildXml(iq)).isNull()
    }

    /**
     * 用 Smack 的解析器解析 IQ（与连接收到报文时相同的流程）。
     *
     * @param xml IQ 报文
     * @return Smack 的 IQ 对象
     */
    private fun parseIq(xml: String): IQ {
        val parser = PacketParserUtils.getParserFor(xml)
        return PacketParserUtils.parseIQ(parser, XmlEnvironment.EMPTY, JxmppContext.getDefaultContext())
    }
}
