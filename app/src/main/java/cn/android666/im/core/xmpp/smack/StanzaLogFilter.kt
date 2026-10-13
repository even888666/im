package cn.android666.im.core.xmpp.smack

/**
 * 判断一条 XMPP 报文是否属于高频"噪声"：流管理确认（XEP-0198 的 `<r/>`、`<a/>`）和心跳（XEP-0199 ping 及其响应）。
 * 噪声不逐条写日志，只按周期计数（Android工程架构.md §8"禁止刷屏"）。
 *
 * 心跳响应是不带子元素的 `<iq type='result' id='...'/>`，单看内容无法与其他空响应区分，
 * 所以记录发出的 ping 的 id，收到同 id 的响应时才算作心跳。线程安全。
 */
internal class StanzaLogFilter {

    /** 判断结果 */
    enum class Kind {
        /** 正常写日志 */
        LOG,

        /** 流管理确认请求 / 应答 */
        SM_ACK,

        /** 心跳请求 */
        PING,

        /** 心跳响应 */
        PING_RESULT,
    }

    /** 已发出、尚未收到响应的 ping 的 id（上限 [MAX_PENDING]，防止服务器不响应时无限增长） */
    private val pendingPingIds = LinkedHashSet<String>()

    /**
     * 判断报文类型。
     *
     * @param element 完整的顶层元素原文
     * @return 报文类型
     */
    @Synchronized
    fun classify(element: String): Kind {
        val text = element.trimStart()
        if (SM_ACK.containsMatchIn(text)) return Kind.SM_ACK
        if (text.startsWith("<iq") && text.contains("urn:xmpp:ping")) {
            ID.find(text)?.groupValues?.get(2)?.let { id ->
                pendingPingIds += id
                if (pendingPingIds.size > MAX_PENDING) pendingPingIds.remove(pendingPingIds.first())
            }
            return Kind.PING
        }
        if (text.startsWith("<iq") && RESULT.containsMatchIn(text)) {
            val id = ID.find(text)?.groupValues?.get(2)
            if (id != null && pendingPingIds.remove(id)) return Kind.PING_RESULT
        }
        return Kind.LOG
    }

    private companion object {
        /** 流管理的 `<r/>` 与 `<a h='..'/>`（只匹配元素开头，避免误伤消息正文） */
        val SM_ACK = Regex("^<(r|a)\\s+xmlns=['\"]urn:xmpp:sm:3['\"]")

        /** iq 的 id 属性 */
        val ID = Regex("\\bid=(['\"])(.*?)\\1")

        /** iq 的 type='result' */
        val RESULT = Regex("\\btype=(['\"])result\\1")

        /** 最多记录的待响应 ping 数 */
        const val MAX_PENDING = 16
    }
}
