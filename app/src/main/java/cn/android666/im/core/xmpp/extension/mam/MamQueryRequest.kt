package cn.android666.im.core.xmpp.extension.mam

import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.Rsm
import cn.android666.im.core.xmpp.iq.RsmResult
import cn.android666.im.core.xmpp.iq.Xml
import cn.android666.im.core.xmpp.iq.XmlNode

/**
 * MAM 查询的结束标记 `<fin>`（XEP-0313）。
 *
 * @property complete 是否已经到达查询范围的末尾（向后翻页时为最新，向前翻页时为最早）
 * @property rsm 本页的分页信息：first / last 为本页第一条、最后一条的存档 ID；本页为空时两者为 null
 */
data class MamFin(val complete: Boolean, val rsm: RsmResult?)

/**
 * MAM 查询（协议规范 §11.2，XEP-0313）。查询结果以 message 报文（带 `<result queryid=…>`）逐条推送，
 * 全部推送完后服务器才返回本 IQ 的结果 `<fin>`。
 *
 * @property queryId 查询 ID（区分推送过来的结果属于哪次查询）
 * @property archive 存档所在 JID：null 为本人归档（单聊），群 JID 为群归档
 * @property max 每页条数
 * @property after 向后翻页：上次最后一条的存档 ID
 * @property before 向前翻页：当前最早一条的存档 ID；空字符串表示从最新一页开始
 * @property start 时间过滤：起始时间（毫秒），含
 * @property end 时间过滤：结束时间（毫秒），含
 * @property with 只查询与某人的消息（bare JID）
 */
class MamQueryRequest(
    val queryId: String,
    val archive: String? = null,
    val max: Int = DEFAULT_MAX,
    val after: String? = null,
    val before: String? = null,
    val start: Long? = null,
    val end: Long? = null,
    val with: String? = null,
) : IqRequest<MamFin>(IqType.SET, "query", MessageCodec.NS_MAM, to = archive) {

    /** 本人归档：不带 to（协议规范 §11.2 示例） */
    override val toOwnAccount: Boolean get() = archive == null

    override val attributes: Map<String, String> = mapOf("queryid" to queryId)

    /**
     * 查询条件表单（有时间或对象过滤时）与分页。
     *
     * @return 内部 XML
     */
    override fun childrenXml(): String = buildString {
        if (start != null || end != null || with != null) {
            append("<x xmlns='jabber:x:data' type='submit'>")
            append("<field var='FORM_TYPE' type='hidden'><value>").append(MessageCodec.NS_MAM).append("</value></field>")
            with?.let { append("<field var='with'><value>").append(Xml.escape(it)).append("</value></field>") }
            start?.let { append("<field var='start'><value>").append(MessageCodec.formatTime(it)).append("</value></field>") }
            end?.let { append("<field var='end'><value>").append(MessageCodec.formatTime(it)).append("</value></field>") }
            append("</x>")
        }
        append(Rsm.requestXml(max, after, before))
    }

    /**
     * 解析 `<fin>`。
     *
     * @param result 结果子元素
     * @return 结束标记
     * @throws IllegalArgumentException 不是 fin 元素
     */
    override fun parseResult(result: XmlNode?): MamFin {
        require(result != null && result.name == "fin") { "MAM 查询结果不是 fin：${result?.name}" }
        return MamFin(complete = result.boolAttr("complete"), rsm = Rsm.parse(result))
    }

    companion object {
        /** 默认每页条数（协议规范 §11.2） */
        const val DEFAULT_MAX = 100
    }
}
