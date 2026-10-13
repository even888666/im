package cn.android666.im.testing

import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.mam.MamQueryRequest
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.XmlNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 假的 MAM 存档（配合 [FakeXmppClient]）：按查询条件（after、start、max）返回一页，
 * 先推送结果报文、再返回 fin，与服务器的行为一致。
 *
 * @property client 假客户端（通过它推送结果）
 */
class FakeArchive(private val client: FakeXmppClient) {

    /**
     * 存档中的一条消息。
     *
     * @property id 存档 ID（按插入顺序递增）
     * @property stamp 存档时间（毫秒）
     * @property messageXml 内层 message（需带 xmlns='jabber:client'）
     */
    data class Item(val id: String, val stamp: Long, val messageXml: String)

    /** 存档内容（按时间顺序） */
    val items = mutableListOf<Item>()

    /** 收到的查询 */
    val queries = mutableListOf<MamQueryRequest>()

    /** 设置后：查询的 after 为该值时返回 item-not-found（模拟游标失效） */
    var invalidCursor: String? = null

    /** 设置后：本页最后一条在返回 fin 之后才推送（模拟 Smack 中 IQ 结果先到） */
    var lateLastIn: CoroutineScope? = null

    /** 设置后：fin 永远不标记 complete（测试单轮页数上限） */
    var neverComplete = false

    private var nextId = 1

    /**
     * 存入一条消息。
     *
     * @param stamp 存档时间
     * @param messageXml 内层 message
     * @return 存档 ID
     */
    fun add(stamp: Long, messageXml: String): String {
        val id = "a${nextId++}"
        items += Item(id, stamp, messageXml)
        return id
    }

    /**
     * 作为 IQ 应答器：处理 MAM 查询，其他 IQ 交给 [fallback]。
     *
     * @param fallback 其他 IQ 的应答
     * @return 应答器
     */
    fun responder(fallback: (IqRequest<*>) -> String? = { null }): (IqRequest<*>) -> String? = { request ->
        if (request is MamQueryRequest) answer(request) else fallback(request)
    }

    /**
     * 应答一次查询。
     *
     * @param request 查询
     * @return fin 的 XML
     */
    private fun answer(request: MamQueryRequest): String {
        queries += request
        if (request.after != null && request.after == invalidCursor) {
            throw XmppException.StanzaError("item-not-found", null)
        }
        val filtered = items.filter { item ->
            (request.start == null || item.stamp >= request.start) &&
                (request.end == null || item.stamp <= request.end) &&
                (request.with == null || request.with in peersOf(item))
        }
        val page: List<Item>
        val complete: Boolean
        if (request.before != null) {
            // 向前翻页：before 之前（空字符串为全部）的最后 max 条；到达最早一条时 complete
            val endIndex = if (request.before.isEmpty()) filtered.size else filtered.indexOfFirst { it.id == request.before }.coerceAtLeast(0)
            val fromIndex = (endIndex - request.max).coerceAtLeast(0)
            page = filtered.subList(fromIndex, endIndex)
            complete = !neverComplete && fromIndex == 0
        } else {
            val fromIndex = request.after?.let { after -> filtered.indexOfFirst { it.id == after } + 1 } ?: 0
            page = filtered.drop(fromIndex).take(request.max)
            complete = !neverComplete && fromIndex + page.size >= filtered.size
        }
        val late = lateLastIn
        page.forEachIndexed { index, item ->
            val xml = resultXml(request.queryId, item)
            if (late != null && index == page.lastIndex) {
                late.launch {
                    delay(100)
                    client.receive(xml)
                }
            } else {
                client.receiveNow(xml)
            }
        }
        val set = if (page.isEmpty()) {
            "<set xmlns='http://jabber.org/protocol/rsm'/>"
        } else {
            "<set xmlns='http://jabber.org/protocol/rsm'><first>${page.first().id}</first><last>${page.last().id}</last></set>"
        }
        return "<fin xmlns='urn:xmpp:mam:2' complete='$complete'>$set</fin>"
    }

    /**
     * 存档消息的双方（from、to 的 bare JID），用于 with 过滤。
     *
     * @param item 存档条目
     * @return 双方 bare JID
     */
    private fun peersOf(item: Item): Set<String> {
        val node = XmlNode.parse(item.messageXml)
        return setOfNotNull(node.attr("from"), node.attr("to")).map { it.substringBefore('/') }.toSet()
    }

    /**
     * 生成一条结果报文。
     *
     * @param queryId 查询 ID
     * @param item 存档条目
     * @return message XML
     */
    private fun resultXml(queryId: String, item: Item): String =
        "<message xmlns='jabber:client' to='10001@im.example.com/android'>" +
            "<result xmlns='urn:xmpp:mam:2' queryid='$queryId' id='${item.id}'><forwarded xmlns='urn:xmpp:forward:0'>" +
            "<delay xmlns='urn:xmpp:delay' stamp='${MessageCodec.formatTime(item.stamp)}'/>${item.messageXml}" +
            "</forwarded></result></message>"
}
