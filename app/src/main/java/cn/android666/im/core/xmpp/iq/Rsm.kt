package cn.android666.im.core.xmpp.iq

/**
 * 结果集分页（XEP-0059 RSM）的请求与结果，协议规范 §4 的分页查询都使用它。
 */
object Rsm {

    /** 命名空间 */
    const val NAMESPACE = "http://jabber.org/protocol/rsm"

    /**
     * 生成分页请求 `<set xmlns='http://jabber.org/protocol/rsm'><max>…</max><after>…</after></set>`。
     *
     * @param max 每页条数
     * @param after 上一页最后一项的游标，第一页为 null
     * @return XML
     */
    fun requestXml(max: Int, after: String? = null): String = buildString {
        append("<set xmlns='").append(NAMESPACE).append("'><max>").append(max).append("</max>")
        if (after != null) append("<after>").append(Xml.escape(after)).append("</after>")
        append("</set>")
    }

    /**
     * 解析分页结果。
     *
     * @param parent 包含 set 子元素的元素
     * @return 分页结果；没有 set 元素时为 null
     */
    fun parse(parent: XmlNode): RsmResult? {
        val set = parent.child("set", NAMESPACE) ?: return null
        return RsmResult(
            first = set.child("first")?.text?.takeIf { it.isNotEmpty() },
            last = set.child("last")?.text?.takeIf { it.isNotEmpty() },
            count = set.child("count")?.text?.toIntOrNull(),
        )
    }
}

/**
 * 分页结果。
 *
 * @property first 本页第一项的游标；本页为空时为 null
 * @property last 本页最后一项的游标（请求下一页时作为 after）；本页为空时为 null
 * @property count 全部结果的数量；服务器没有返回时为 null
 */
data class RsmResult(val first: String?, val last: String?, val count: Int?)
