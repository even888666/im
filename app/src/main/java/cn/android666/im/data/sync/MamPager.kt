package cn.android666.im.data.sync

import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.extension.mam.MamFin
import cn.android666.im.core.xmpp.extension.mam.MamQueryRequest
import cn.android666.im.core.xmpp.extension.msg.IncomingMessage
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageEnvelope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MAM 查询的一页结果。
 *
 * @property items 按时间顺序的结果
 * @property fin 结束标记（分页信息）
 */
data class MamPage(val items: List<IncomingMessage>, val fin: MamFin)

/**
 * 执行一次 MAM 查询并收集推送过来的结果（XEP-0313）：先订阅收到的报文，再发送查询；
 * 收到 `<fin>` 后等待本页最后一条（fin 中的 last）到达。
 *
 * 为什么要等：Smack 把 IQ 结果交给等待者，与把 message 交给监听器是两条路径，IQ 结果可能先于前面几条消息到达业务层
 * （开发环境搭建 4.4.19）。
 *
 * @property client XMPP 客户端
 * @property lastWaitMillis 等待最后一条结果的上限
 */
class MamPager(private val client: XmppClient, private val lastWaitMillis: Long = LAST_WAIT_MILLIS) {

    private val log = Logs.file(TAG)

    /**
     * 查询一页。
     *
     * @param request 查询
     * @return 本页结果
     * @throws cn.android666.im.core.xmpp.XmppException 未连接、超时、服务器返回错误
     */
    suspend fun query(request: MamQueryRequest): MamPage = coroutineScope {
        val results = Channel<IncomingMessage>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.incomingMessages.collect { xml ->
                val parsed = try {
                    MessageCodec.parse(xml)
                } catch (e: IllegalArgumentException) {
                    return@collect
                }
                if ((parsed.envelope as? MessageEnvelope.Mam)?.queryId == request.queryId) results.send(parsed)
            }
        }
        try {
            val fin = client.sendIq(request)
            val items = mutableListOf<IncomingMessage>()
            val last = fin.rsm?.last
            if (last != null) {
                val arrived = withTimeoutOrNull(lastWaitMillis) {
                    while (true) {
                        val item = results.receive()
                        items += item
                        if ((item.envelope as MessageEnvelope.Mam).stanzaId == last) break
                    }
                }
                if (arrived == null) log.warn("等待 MAM 最后一条结果超时：queryId={}，已收到 {} 条", request.queryId, items.size)
            }
            while (true) items += results.tryReceive().getOrNull() ?: break
            MamPage(items, fin)
        } finally {
            collector.cancel()
        }
    }

    private companion object {
        const val TAG = "MamPager"

        /** 等待最后一条结果的上限 */
        const val LAST_WAIT_MILLIS = 5_000L
    }
}
