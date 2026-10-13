package cn.android666.im.core.xmpp.extension.presence

import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.Xml
import cn.android666.im.core.xmpp.iq.XmlNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 某个在线端的状态（协议规范 §5.3）。
 *
 * @property resource 端：android / pc / web
 * @property show away / dnd 等；在线时为 null
 * @property status 自定义文字
 * @property code 自定义状态码（busy、meeting、business-trip、away），见协议规范 §5.2
 */
data class ResourcePresence(val resource: String, val show: String?, val status: String?, val code: String?)

/**
 * 一个同事的在线状态。
 *
 * @property jid bare JID
 * @property online 是否有任一端在线
 * @property resources 在线的端
 * @property lastActiveMillis 离线时最后活跃的时间（毫秒）；未知时为 null
 */
data class PresenceQueryItem(
    val jid: String,
    val online: Boolean,
    val resources: List<ResourcePresence>,
    val lastActiveMillis: Long?,
)

/**
 * 查询任意同事的在线状态（协议规范 §5.3），不需要订阅，每次最多 100 个。
 *
 * @property jids 同事的 bare JID
 */
class PresenceQueryRequest(val jids: List<String>) :
    IqRequest<List<PresenceQueryItem>>(IqType.GET, ELEMENT, NAMESPACE) {

    init {
        require(jids.size <= MAX_ITEMS) { "每次最多查询 $MAX_ITEMS 个：${jids.size}" }
    }

    override fun childrenXml(): String = jids.joinToString("") { "<item jid='${Xml.escape(it)}'/>" }

    /**
     * 解析结果。
     *
     * @param result 结果子元素
     * @return 各同事的在线状态
     */
    override fun parseResult(result: XmlNode?): List<PresenceQueryItem> {
        require(result != null && result.name == ELEMENT && result.namespace == NAMESPACE) { "在线状态结果格式错误：$result" }
        return result.childrenNamed("item").map { item ->
            PresenceQueryItem(
                jid = requireNotNull(item.attr("jid")) { "缺少 jid：$item" },
                online = item.boolAttr("online"),
                resources = item.childrenNamed("resource").map {
                    ResourcePresence(it.attr("name").orEmpty(), it.attr("show"), it.attr("status"), it.attr("code"))
                },
                lastActiveMillis = item.attr("lastActive")?.let(::parseTime),
            )
        }
    }

    /**
     * 解析 XEP-0082 时间。
     *
     * @param text 时间文本，如 2026-10-13T01:20:00.000Z
     * @return 毫秒；格式错误时为 null（按"未知"处理，不影响其他字段）
     */
    private fun parseTime(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    companion object {
        /** 命名空间 */
        const val NAMESPACE = "urn:android666:im:presence:1"

        /** 元素名 */
        const val ELEMENT = "presence-query"

        /** 每次查询的上限（协议规范 §1.6） */
        const val MAX_ITEMS = 100
    }
}
