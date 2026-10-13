package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.common.Clock
import cn.android666.im.core.log.LogSanitizer
import cn.android666.im.core.log.PeriodicCounter
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.debugger.SmackDebugger
import org.jivesoftware.smack.debugger.SmackDebuggerFactory
import org.jivesoftware.smack.packet.TopLevelStreamElement
import org.jxmpp.jid.EntityFullJid

/**
 * Smack 调试器：把收发的 XMPP 报文**脱敏后**写入 xmpp 日志（Android工程架构.md §8）。
 *
 * - 使用 [onOutgoingStreamElement] / [onIncomingStreamElement]：拿到的是已解析的**完整**顶层元素，
 *   用 toXML() 输出，保证 SASL 认证内容不会被切开而逃过脱敏。不使用 [outgoingStreamSink] /
 *   [incomingStreamSink]：实测收到方向的原文会按网络读取的分块切开（例如把一个属性切成两半），
 *   敏感内容跨块时正则匹配不到；
 * - 流的开头（`<stream:stream>`）、stream features，以及客户端发出的 nonza（SASL 的 `<auth>` / `<response>`、
 *   流管理的 `<enable>` / `<r>` 等）不经过上述回调，不会被记录（实测）；连接、登录过程由业务日志记录；
 * - 流管理确认、心跳等高频报文不逐条记录，用 [PeriodicCounter] 按周期汇总；
 * - [enabled] 每次写日志前判断，可在运行时开关（Release 默认关闭，见开发计划 8.10）。
 *
 * @param connection Smack 连接
 * @property enabled 是否记录报文
 * @property sink 日志输出（SmackXmppClient 默认写入 xmpp 日志）
 * @param clock 周期统计的时间来源
 */
internal class XmppLogDebugger(
    connection: XMPPConnection,
    private val enabled: () -> Boolean,
    private val sink: (String) -> Unit,
    clock: Clock,
) : SmackDebugger(connection) {

    /** 噪声报文判断 */
    private val filter = StanzaLogFilter()

    /** 噪声报文的周期统计，汇总也写入 xmpp 日志 */
    private val noiseCounter = PeriodicCounter("XMPP 心跳与流管理", clock = clock, output = sink)

    override fun userHasLogged(user: EntityFullJid) {
        if (enabled()) sink("已登录：$user")
    }

    /** 原始字节流不记录（原因见类注释） */
    override fun outgoingStreamSink(outgoing: CharSequence) = Unit

    /** 原始字节流不记录（原因见类注释） */
    override fun incomingStreamSink(incoming: CharSequence) = Unit

    override fun onIncomingStreamElement(streamElement: TopLevelStreamElement) =
        record("RECV", streamElement.toXML())

    override fun onOutgoingStreamElement(streamElement: TopLevelStreamElement) =
        record("SENT", streamElement.toXML())

    /**
     * 记录一条报文：噪声只计数，其他脱敏后输出。
     *
     * @param direction `SENT` 或 `RECV`
     * @param element 完整的顶层元素
     */
    private fun record(direction: String, element: CharSequence) {
        if (!enabled()) return
        val text = element.toString()
        if (text.isBlank()) return
        when (val kind = filter.classify(text)) {
            StanzaLogFilter.Kind.LOG -> sink("$direction ${LogSanitizer.sanitizeStanza(text)}")
            else -> noiseCounter.record("$direction ${kind.name}")
        }
    }

    /**
     * 创建 [XmppLogDebugger] 的工厂，设置到 Smack 连接配置中。
     *
     * @property enabled 是否记录报文
     * @property clock 时间来源
     * @property sink 日志输出
     */
    class Factory(
        private val enabled: () -> Boolean,
        private val clock: Clock,
        private val sink: (String) -> Unit,
    ) : SmackDebuggerFactory {
        override fun create(connection: XMPPConnection): SmackDebugger =
            XmppLogDebugger(connection, enabled, sink, clock)
    }
}
