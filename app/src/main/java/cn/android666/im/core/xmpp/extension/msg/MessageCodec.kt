package cn.android666.im.core.xmpp.extension.msg

import cn.android666.im.core.xmpp.iq.Xml
import cn.android666.im.core.xmpp.iq.XmlNode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 消息报文的编解码（协议规范 §6 ~ §8、§11），与 Smack 无关：
 * - [parse]：收到的 message XML → [IncomingMessage]（含 Carbons、MAM 外层包装）；
 * - chat / receipt / displayed / retract / chatState：生成要发送的 message XML。
 *
 * 不认识的元素和属性一律忽略（协议规范 §1.5）。可以直接用协议规范中的示例做单元测试。
 */
object MessageCodec {

    /** 自定义消息内容 */
    const val NS_MSG = "urn:android666:im:msg:1"

    /** 自定义错误码 */
    const val NS_ERROR = "urn:android666:im:error:1"

    /** XEP-0359 origin-id / stanza-id */
    const val NS_SID = "urn:xmpp:sid:0"

    /** XEP-0184 送达回执 */
    const val NS_RECEIPTS = "urn:xmpp:receipts"

    /** XEP-0333 已读标记 */
    const val NS_MARKERS = "urn:xmpp:chat-markers:0"

    /** XEP-0085 正在输入 */
    const val NS_CHAT_STATES = "http://jabber.org/protocol/chatstates"

    /** XEP-0424 撤回 */
    const val NS_RETRACT = "urn:xmpp:message-retract:1"

    /** XEP-0428 降级文本标记 */
    const val NS_FALLBACK = "urn:xmpp:fallback:0"

    /** XEP-0461 引用回复 */
    const val NS_REPLY = "urn:xmpp:reply:0"

    /** XEP-0372 引用（@） */
    const val NS_REFERENCE = "urn:xmpp:reference:0"

    /** XEP-0334 处理提示 */
    const val NS_HINTS = "urn:xmpp:hints"

    /** XEP-0203 延迟投递 */
    const val NS_DELAY = "urn:xmpp:delay"

    /** XEP-0297 转发 */
    const val NS_FORWARD = "urn:xmpp:forward:0"

    /** XEP-0280 Carbons */
    const val NS_CARBONS = "urn:xmpp:carbons:2"

    /** XEP-0313 MAM */
    const val NS_MAM = "urn:xmpp:mam:2"

    /** 标准 stanza 错误条件 */
    private const val NS_STANZAS = "urn:ietf:params:xml:ns:xmpp-stanzas"

    /** XEP-0085 的状态元素名 */
    private val CHAT_STATES = setOf("active", "composing", "paused", "inactive", "gone")

    // ---------------------------------------------------------------- 解析

    /**
     * 解析收到的 message 报文。
     *
     * @param xml message 元素的 XML
     * @return 解析结果
     * @throws IllegalArgumentException 不是 message 元素或 XML 格式错误
     */
    fun parse(xml: String): IncomingMessage = parse(XmlNode.parse(xml))

    /**
     * 解析收到的 message 元素：先识别 Carbons（sent / received）和 MAM（result）包装，再解析内层消息。
     *
     * @param node message 元素
     * @return 解析结果
     * @throws IllegalArgumentException 不是 message 元素
     */
    fun parse(node: XmlNode): IncomingMessage {
        require(node.name == "message") { "不是 message 元素：${node.name}" }
        val outerFrom = node.attr("from")
        for (child in node.children) {
            if (child.namespace == NS_CARBONS && (child.name == "sent" || child.name == "received")) {
                val inner = forwardedMessage(child)
                if (inner != null) {
                    return IncomingMessage(MessageEnvelope.Carbon(child.name == "sent", outerFrom), parseMessage(inner.first, inner.second))
                }
            }
            if (child.namespace == NS_MAM && child.name == "result") {
                val inner = forwardedMessage(child)
                val stanzaId = child.attr("id")
                if (inner != null && stanzaId != null) {
                    val envelope = MessageEnvelope.Mam(child.attr("queryid"), stanzaId, inner.second, outerFrom)
                    return IncomingMessage(envelope, parseMessage(inner.first, inner.second))
                }
            }
        }
        return IncomingMessage(null, parseMessage(node, null))
    }

    /**
     * 取出 `<forwarded>` 中的 message 和 delay 时间。
     *
     * @param wrapper sent / received / result 元素
     * @return 内层 message 与 forwarded 中的 delay 时间；没有时为 null
     */
    private fun forwardedMessage(wrapper: XmlNode): Pair<XmlNode, Long?>? {
        val forwarded = wrapper.child("forwarded", NS_FORWARD) ?: return null
        val message = forwarded.child("message") ?: return null
        val stamp = forwarded.child("delay", NS_DELAY)?.attr("stamp")?.let(::parseTime)
        return message to stamp
    }

    /**
     * 解析一条（非包装的）message。
     *
     * @param node message 元素
     * @param outerStamp 外层 forwarded 中的 delay 时间（MAM、Carbons），优先于内层的 delay
     * @return 消息
     */
    private fun parseMessage(node: XmlNode, outerStamp: Long?): MessageStanza {
        val body = node.child("body")?.rawText
        var content: MessageContent? = null
        var originId: String? = null
        var requestReceipt = false
        var markable = false
        var receivedId: String? = null
        var displayedId: String? = null
        var chatState: String? = null
        var retractId: String? = null
        var reply: ReplyRef? = null
        val mentions = mutableListOf<Mention>()
        var delay: Long? = null
        val stanzaIds = mutableListOf<StanzaId>()
        var error: MessageError? = null
        for (child in node.children) {
            when (child.namespace) {
                NS_MSG -> if (child.name == "content") content = parseContent(child)
                NS_SID -> when (child.name) {
                    "origin-id" -> originId = child.attr("id")
                    "stanza-id" -> {
                        val id = child.attr("id")
                        val by = child.attr("by")
                        if (id != null && by != null) stanzaIds += StanzaId(id, by)
                    }
                }
                NS_RECEIPTS -> when (child.name) {
                    "request" -> requestReceipt = true
                    "received" -> receivedId = child.attr("id")
                }
                NS_MARKERS -> when (child.name) {
                    "markable" -> markable = true
                    "displayed" -> displayedId = child.attr("id")
                }
                NS_CHAT_STATES -> if (child.name in CHAT_STATES) chatState = child.name
                NS_RETRACT -> if (child.name == "retract") retractId = child.attr("id")
                NS_REPLY -> if (child.name == "reply") {
                    val to = child.attr("to")
                    val id = child.attr("id")
                    if (to != null && id != null) reply = ReplyRef(to, id)
                }
                NS_REFERENCE -> if (child.name == "reference" && child.attr("type") == "mention") {
                    val begin = child.attr("begin")?.toIntOrNull()
                    val end = child.attr("end")?.toIntOrNull()
                    val jid = child.attr("uri")?.removePrefix("xmpp:")
                    if (begin != null && end != null && jid != null) mentions += Mention(begin, end, jid)
                }
                NS_DELAY -> if (child.name == "delay") delay = child.attr("stamp")?.let(::parseTime)
                else -> if (child.name == "error") error = parseError(child)
            }
        }
        // 没有 content 元素：有正文即为文本（撤回的降级文本除外）
        if (content == null && body != null && retractId == null) content = MessageContent.Text
        return MessageStanza(
            id = node.attr("id"),
            type = node.attr("type") ?: "normal",
            from = node.attr("from"),
            to = node.attr("to"),
            body = body,
            content = content,
            originId = originId,
            requestReceipt = requestReceipt,
            markable = markable,
            receivedId = receivedId,
            displayedId = displayedId,
            chatState = chatState,
            retractId = retractId,
            reply = reply,
            mentions = mentions,
            delayStamp = outerStamp ?: delay,
            stanzaIds = stanzaIds,
            error = error,
        )
    }

    /**
     * 解析 `<content>`。
     *
     * @param node content 元素
     * @return 内容；必要的子元素缺失时为 [MessageContent.Unknown]
     */
    internal fun parseContent(node: XmlNode): MessageContent {
        val type = node.attr("type") ?: "text"
        val file = node.child("file")?.let(::parseFile)
        val thumbnail = node.child("thumbnail")?.let(::parseThumbnail)
        return when (type) {
            "text" -> MessageContent.Text
            "image" -> file?.let { MessageContent.Image(it, thumbnail, node.boolAttr("original")) }
            "voice" -> file?.let { MessageContent.Voice(it) }
            "video" -> file?.let { MessageContent.Video(it, thumbnail) }
            "file" -> file?.let { MessageContent.File(it) }
            "card" -> node.child("card")?.let { card ->
                card.attr("jid")?.let { MessageContent.Card(it, card.attr("name").orEmpty()) }
            }
            "merged" -> MessageContent.Merged(
                title = node.attr("title").orEmpty(),
                items = node.children.filter { it.name == "forwarded" && it.namespace == NS_FORWARD }.mapNotNull(::parseForwardedItem),
            )
            "call" -> node.child("call")?.let { call ->
                val callId = call.attr("callId")
                val media = call.attr("media")
                val result = call.attr("result")
                if (callId != null && media != null && result != null) {
                    MessageContent.Call(callId, media, result, call.attr("duration")?.toIntOrNull())
                } else {
                    null
                }
            }
            else -> null
        } ?: MessageContent.Unknown(type)
    }

    /**
     * 解析合并转发中的一条消息。
     *
     * @param forwarded forwarded 元素
     * @return 消息；缺少 message 时为 null
     */
    private fun parseForwardedItem(forwarded: XmlNode): ForwardedItem? {
        val message = forwarded.child("message") ?: return null
        val stamp = forwarded.child("delay", NS_DELAY)?.attr("stamp")?.let(::parseTime)
        val parsed = parseMessage(message, stamp)
        return ForwardedItem(
            id = parsed.msgId.orEmpty(),
            from = parsed.from.orEmpty(),
            body = parsed.body.orEmpty(),
            content = parsed.content ?: MessageContent.Text,
            stamp = parsed.delayStamp,
        )
    }

    /**
     * 解析 `<file>`。
     *
     * @param node file 元素
     * @return 文件描述；缺少 url 时为 null
     */
    private fun parseFile(node: XmlNode): FileInfo? {
        val url = node.attr("url") ?: return null
        return FileInfo(
            url = url,
            name = node.attr("name").orEmpty(),
            size = node.attr("size")?.toLongOrNull() ?: 0,
            mime = node.attr("mime") ?: "application/octet-stream",
            sha256 = node.attr("sha256"),
            width = node.attr("width")?.toIntOrNull(),
            height = node.attr("height")?.toIntOrNull(),
            duration = node.attr("duration")?.toLongOrNull(),
        )
    }

    /**
     * 解析 `<thumbnail>`。
     *
     * @param node thumbnail 元素
     * @return 缩略图；缺少 url 时为 null
     */
    private fun parseThumbnail(node: XmlNode): ThumbnailInfo? {
        val url = node.attr("url") ?: return null
        return ThumbnailInfo(
            url = url,
            width = node.attr("width")?.toIntOrNull() ?: 0,
            height = node.attr("height")?.toIntOrNull() ?: 0,
            mime = node.attr("mime") ?: "image/jpeg",
        )
    }

    /**
     * 解析 `<error>`。
     *
     * @param node error 元素
     * @return 错误
     */
    private fun parseError(node: XmlNode): MessageError {
        val condition = node.children.firstOrNull { it.namespace == NS_STANZAS && it.name != "text" }?.name ?: "undefined-condition"
        val appCode = node.child("app-error", NS_ERROR)?.attr("code")
        return MessageError(condition, appCode)
    }

    /**
     * 解析 XEP-0082 时间（如 2026-10-13T02:30:00.000Z，也接受带时区偏移的格式）。
     *
     * @param text 时间文本
     * @return 毫秒；格式错误时为 null
     */
    internal fun parseTime(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        try {
            Instant.parse(text).toEpochMilli()
        } catch (e2: DateTimeParseException) {
            null
        }
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 生成一条用户可见的消息（协议规范 §6.1）：body + origin-id + content + 送达回执请求（单聊）+ markable。
     *
     * @param to 接收方 bare JID（单聊）或群 JID
     * @param id 消息 ID
     * @param body 正文；非文本消息为降级文本（见 [fallbackBody]）
     * @param content 内容
     * @param groupChat 是否群聊（type='groupchat'，不请求送达回执）
     * @param reply 引用回复
     * @param mentions @ 提醒（群聊）
     * @return message XML
     */
    fun chat(
        to: String,
        id: String,
        body: String,
        content: MessageContent,
        groupChat: Boolean = false,
        reply: ReplyRef? = null,
        mentions: List<Mention> = emptyList(),
    ): String = buildString {
        append("<message type='").append(if (groupChat) "groupchat" else "chat").append("' id='").append(Xml.escape(id))
        append("' to='").append(Xml.escape(to)).append("'>")
        append("<body>").append(Xml.escape(body)).append("</body>")
        append("<origin-id xmlns='").append(NS_SID).append("' id='").append(Xml.escape(id)).append("'/>")
        appendContent(this, content)
        reply?.let {
            append("<reply xmlns='").append(NS_REPLY).append("' to='").append(Xml.escape(it.to))
            append("' id='").append(Xml.escape(it.id)).append("'/>")
        }
        mentions.forEach {
            append("<reference xmlns='").append(NS_REFERENCE).append("' type='mention' begin='").append(it.begin)
            append("' end='").append(it.end).append("' uri='xmpp:").append(Xml.escape(it.jid)).append("'/>")
        }
        if (!groupChat) append("<request xmlns='").append(NS_RECEIPTS).append("'/>")
        append("<markable xmlns='").append(NS_MARKERS).append("'/>")
        append("</message>")
    }

    /**
     * 生成 content 元素（文本也带 content type='text'，与协议示例一致）。
     *
     * @param sb 输出
     * @param content 内容
     */
    private fun appendContent(sb: StringBuilder, content: MessageContent) {
        sb.append("<content xmlns='").append(NS_MSG).append("' type='").append(content.type)
        when (content) {
            is MessageContent.Text, is MessageContent.Unknown -> {
                sb.append("'/>")
                return
            }
            is MessageContent.Image -> sb.append("' original='").append(content.original).append("'>")
            is MessageContent.Merged -> sb.append("' title='").append(Xml.escape(content.title)).append("'>")
            else -> sb.append("'>")
        }
        when (content) {
            is MessageContent.Image -> {
                appendFile(sb, content.file)
                content.thumbnail?.let { appendThumbnail(sb, it) }
            }
            is MessageContent.Voice -> appendFile(sb, content.file)
            is MessageContent.Video -> {
                appendFile(sb, content.file)
                content.thumbnail?.let { appendThumbnail(sb, it) }
            }
            is MessageContent.File -> appendFile(sb, content.file)
            is MessageContent.Card ->
                sb.append("<card jid='").append(Xml.escape(content.jid)).append("' name='").append(Xml.escape(content.name)).append("'/>")
            is MessageContent.Merged -> content.items.forEach { item ->
                sb.append("<forwarded xmlns='").append(NS_FORWARD).append("'>")
                item.stamp?.let { sb.append("<delay xmlns='").append(NS_DELAY).append("' stamp='").append(formatTime(it)).append("'/>") }
                // 内层 message 必须显式声明 jabber:client，否则按 XML 规则会继承 forwarded 的命名空间
                sb.append("<message xmlns='jabber:client' type='chat' id='").append(Xml.escape(item.id)).append("' from='").append(Xml.escape(item.from)).append("'>")
                sb.append("<body>").append(Xml.escape(item.body)).append("</body>")
                appendContent(sb, item.content)
                sb.append("</message></forwarded>")
            }
            is MessageContent.Call -> {
                sb.append("<call callId='").append(Xml.escape(content.callId)).append("' media='").append(Xml.escape(content.media))
                sb.append("' result='").append(Xml.escape(content.result)).append('\'')
                content.duration?.let { sb.append(" duration='").append(it).append('\'') }
                sb.append("/>")
            }
            is MessageContent.Text, is MessageContent.Unknown -> Unit
        }
        sb.append("</content>")
    }

    /**
     * 生成 `<file>`。
     *
     * @param sb 输出
     * @param file 文件描述
     */
    private fun appendFile(sb: StringBuilder, file: FileInfo) {
        sb.append("<file url='").append(Xml.escape(file.url)).append("' name='").append(Xml.escape(file.name))
        sb.append("' size='").append(file.size).append("' mime='").append(Xml.escape(file.mime)).append('\'')
        file.sha256?.let { sb.append(" sha256='").append(Xml.escape(it)).append('\'') }
        file.width?.let { sb.append(" width='").append(it).append('\'') }
        file.height?.let { sb.append(" height='").append(it).append('\'') }
        file.duration?.let { sb.append(" duration='").append(it).append('\'') }
        sb.append("/>")
    }

    /**
     * 生成 `<thumbnail>`。
     *
     * @param sb 输出
     * @param thumbnail 缩略图
     */
    private fun appendThumbnail(sb: StringBuilder, thumbnail: ThumbnailInfo) {
        sb.append("<thumbnail url='").append(Xml.escape(thumbnail.url)).append("' width='").append(thumbnail.width)
        sb.append("' height='").append(thumbnail.height).append("' mime='").append(Xml.escape(thumbnail.mime)).append("'/>")
    }

    /**
     * 送达回执（协议规范 §8.1）：`<received>` + `<no-store/>`。
     *
     * @param to 原消息的发送方（完整 JID，回给对方发消息的那一端）
     * @param receivedId 被确认的消息 ID
     * @param id 回执报文自己的 ID
     * @return message XML
     */
    fun receipt(to: String, receivedId: String, id: String): String =
        "<message to='${Xml.escape(to)}' id='${Xml.escape(id)}'>" +
            "<received xmlns='$NS_RECEIPTS' id='${Xml.escape(receivedId)}'/>" +
            "<no-store xmlns='$NS_HINTS'/></message>"

    /**
     * 已读标记（协议规范 §8.2）：`<displayed>` + `<store/>`（进入存档，发送方离线时上线后也能拿到）。
     *
     * @param to 对方 bare JID
     * @param displayedId 被标记已读的消息 ID（该消息及之前的都已读）
     * @param id 报文自己的 ID
     * @return message XML
     */
    fun displayed(to: String, displayedId: String, id: String): String =
        "<message type='chat' to='${Xml.escape(to)}' id='${Xml.escape(id)}'>" +
            "<displayed xmlns='$NS_MARKERS' id='${Xml.escape(displayedId)}'/>" +
            "<store xmlns='$NS_HINTS'/></message>"

    /**
     * 撤回（协议规范 §8.4）：降级文本 + `<retract>` + `<fallback>` + `<store/>`。
     *
     * @param to 对方 bare JID 或群 JID
     * @param retractId 被撤回的消息 ID
     * @param body 降级文本，如"张三撤回了一条消息"
     * @param id 报文自己的 ID
     * @param groupChat 是否群聊
     * @return message XML
     */
    fun retract(to: String, retractId: String, body: String, id: String, groupChat: Boolean = false): String =
        "<message type='${if (groupChat) "groupchat" else "chat"}' to='${Xml.escape(to)}' id='${Xml.escape(id)}'>" +
            "<body>${Xml.escape(body)}</body>" +
            "<retract xmlns='$NS_RETRACT' id='${Xml.escape(retractId)}'/>" +
            "<fallback xmlns='$NS_FALLBACK' for='$NS_RETRACT'/>" +
            "<store xmlns='$NS_HINTS'/></message>"

    /**
     * 正在输入状态（协议规范 §8.3，仅单聊）：composing / paused + `<no-store/>`。
     *
     * @param to 对方 bare JID
     * @param state composing 或 paused
     * @param id 报文自己的 ID
     * @return message XML
     */
    fun chatState(to: String, state: String, id: String): String {
        require(state in CHAT_STATES) { "不支持的状态：$state" }
        return "<message type='chat' to='${Xml.escape(to)}' id='${Xml.escape(id)}'>" +
            "<$state xmlns='$NS_CHAT_STATES'/><no-store xmlns='$NS_HINTS'/></message>"
    }

    /**
     * 非文本消息的降级文本（协议规范 §7 表格）。
     *
     * @param content 内容
     * @param text 文本消息的原文
     * @return body
     */
    fun fallbackBody(content: MessageContent, text: String = ""): String = when (content) {
        is MessageContent.Text -> text
        is MessageContent.Image -> "[图片]"
        is MessageContent.Voice -> "[语音]"
        is MessageContent.Video -> "[视频]"
        is MessageContent.File -> "[文件] ${content.file.name}"
        is MessageContent.Card -> "[名片] ${content.name}"
        is MessageContent.Merged -> "[聊天记录] ${content.title}"
        is MessageContent.Call -> "[${if (content.media == "video") "视频" else "语音"}通话] ${callResultText(content)}"
        is MessageContent.Unknown -> text
    }

    /**
     * 通话结果的文字（降级文本和界面共用）。
     *
     * @param call 通话记录
     * @return 如"通话时长 03:25""已取消""对方已拒绝"
     */
    fun callResultText(call: MessageContent.Call): String = when (call.result) {
        "completed" -> {
            val seconds = call.duration ?: 0
            "通话时长 %02d:%02d".format(seconds / 60, seconds % 60)
        }
        "canceled" -> "已取消"
        "rejected" -> "对方已拒绝"
        "missed" -> "无人接听"
        "busy" -> "对方忙"
        else -> "连接失败"
    }

    /**
     * 格式化为 XEP-0082 时间（UTC，精确到毫秒）。
     *
     * @param millis 毫秒
     * @return 如 2026-10-13T02:30:00.000Z
     */
    fun formatTime(millis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(millis))

    /** XEP-0082 时间格式（协议规范 §1.4） */
    private val TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)
}
