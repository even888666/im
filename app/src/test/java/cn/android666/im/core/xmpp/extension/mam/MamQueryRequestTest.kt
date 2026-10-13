package cn.android666.im.core.xmpp.extension.mam

import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.Rsm
import cn.android666.im.core.xmpp.iq.RsmResult
import cn.android666.im.core.xmpp.iq.XmlNode
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** [MamQueryRequest] 测试：协议规范 §11.2 的查询报文与 fin 解析 */
class MamQueryRequestTest {

    /** §11.2 示例：本人归档，从游标之后，每页 100 条；不带 to */
    @Test
    fun selfArchive_afterCursor() {
        val request = MamQueryRequest(queryId = "q1", after = "s-9")

        assertThat(request.type).isEqualTo(IqType.SET)
        assertThat(request.toOwnAccount).isTrue()
        assertThat(request.toChildXml()).isEqualTo(
            "<query xmlns='urn:xmpp:mam:2' queryid='q1'>" +
                "<set xmlns='http://jabber.org/protocol/rsm'><max>100</max><after>s-9</after></set></query>",
        )
    }

    /** 群归档：to 为群 JID；时间与对象过滤用数据表单 */
    @Test
    fun groupArchive_withForm() {
        val request = MamQueryRequest(queryId = "q2", archive = "g1@conference.im.example.com", start = 1_791_853_200_000, with = "a@b", max = 20)

        assertThat(request.toOwnAccount).isFalse()
        assertThat(request.to).isEqualTo("g1@conference.im.example.com")
        val node = XmlNode.parse(request.toChildXml())
        val form = node.child("x", "jabber:x:data")!!
        assertThat(form.attr("type")).isEqualTo("submit")
        val fields = form.childrenNamed("field").associate { it.attr("var") to it.child("value")!!.text }
        assertThat(fields).containsExactly(
            "FORM_TYPE", "urn:xmpp:mam:2",
            "with", "a@b",
            "start", "2026-10-13T01:00:00.000Z",
        )
        assertThat(node.child("set", Rsm.NAMESPACE)!!.child("max")!!.text).isEqualTo("20")
    }

    /** 向前翻页：空字符串为 `<before/>`（最新一页），否则为指定游标之前 */
    @Test
    fun before() {
        assertThat(MamQueryRequest("q", before = "").toChildXml()).contains("<max>100</max><before/></set>")
        assertThat(MamQueryRequest("q", before = "s-1").toChildXml()).contains("<before>s-1</before>")
        assertThat(Rsm.requestXml(5)).isEqualTo("<set xmlns='http://jabber.org/protocol/rsm'><max>5</max></set>")
    }

    @Test
    fun parseFin() {
        val request = MamQueryRequest("q")
        val fin = request.parseResult(
            XmlNode.parse(
                "<fin xmlns='urn:xmpp:mam:2' complete='true'><set xmlns='http://jabber.org/protocol/rsm'>" +
                    "<first index='0'>332</first><last>334</last><count>3</count></set></fin>",
            ),
        )
        assertThat(fin).isEqualTo(MamFin(complete = true, rsm = RsmResult("332", "334", 3)))

        // 空结果、没有 complete
        val empty = request.parseResult(XmlNode.parse("<fin xmlns='urn:xmpp:mam:2'><set xmlns='http://jabber.org/protocol/rsm'/></fin>"))
        assertThat(empty).isEqualTo(MamFin(complete = false, rsm = RsmResult(null, null, null)))

        assertThrows(IllegalArgumentException::class.java) { request.parseResult(null) }
        assertThrows(IllegalArgumentException::class.java) { request.parseResult(XmlNode.parse("<query xmlns='urn:xmpp:mam:2'/>")) }
    }
}
