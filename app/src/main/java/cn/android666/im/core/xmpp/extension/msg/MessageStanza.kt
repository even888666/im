package cn.android666.im.core.xmpp.extension.msg

/**
 * 引用回复（协议规范 §8.5，XEP-0461）。
 *
 * @property to 被引用消息的发送方 bare JID
 * @property id 被引用消息的消息 ID
 */
data class ReplyRef(val to: String, val id: String)

/**
 * @ 提醒（协议规范 §8.6，XEP-0372 reference）。begin / end 按 Unicode 码点计数。
 *
 * @property begin 起始码点（含）
 * @property end 结束码点（不含）
 * @property jid 被 @ 的用户 JID；为群 JID 时表示 @所有人
 */
data class Mention(val begin: Int, val end: Int, val jid: String) {

    /**
     * 换算为 Kotlin 字符串（UTF-16）中的下标范围，用于界面高亮。
     *
     * @param text 消息正文
     * @return [start, end) 的 UTF-16 下标；超出正文范围时为 null
     */
    fun utf16Range(text: String): IntRange? {
        val codePoints = text.codePointCount(0, text.length)
        if (begin < 0 || end < begin || end > codePoints) return null
        val start = text.offsetByCodePoints(0, begin)
        val stop = text.offsetByCodePoints(0, end)
        return start until stop
    }

    companion object {

        /**
         * 由 UTF-16 下标创建（输入框中的光标位置都是 UTF-16 下标），换算为码点。
         *
         * @param text 消息正文
         * @param start 起始 UTF-16 下标（含）
         * @param end 结束 UTF-16 下标（不含）
         * @param jid 被 @ 的用户 JID 或群 JID
         * @return @ 提醒
         */
        fun fromUtf16(text: String, start: Int, end: Int, jid: String): Mention =
            Mention(text.codePointCount(0, start), text.codePointCount(0, end), jid)
    }
}

/**
 * MAM 存档中服务器分配的 stanza-id（XEP-0359），只用作补拉游标（协议规范 §1.3）。
 *
 * @property id stanza-id
 * @property by 分配者（本人 bare JID 或群 JID）
 */
data class StanzaId(val id: String, val by: String)

/**
 * 消息中的错误（type='error'）。
 *
 * @property condition 标准错误条件，如 policy-violation
 * @property appErrorCode 自定义错误码（协议规范 §16），如 sensitive-word
 */
data class MessageError(val condition: String, val appErrorCode: String?)

/**
 * 解析后的一条 message 报文（只包含本项目使用的字段，不认识的元素忽略，协议规范 §1.5）。
 *
 * @property id message@id（消息 ID）
 * @property type chat / groupchat / normal / error / headline
 * @property from 发送方（完整 JID）
 * @property to 接收方
 * @property body 正文 / 降级文本
 * @property content 内容；没有 content 元素时，有 body 即为 [MessageContent.Text]，否则为 null（回执等）
 * @property originId origin-id（应与 id 相同）
 * @property requestReceipt 是否请求送达回执
 * @property markable 是否可标记已读
 * @property receivedId 送达回执 `<received id>`：被确认送达的消息 ID
 * @property displayedId 已读标记 `<displayed id>`：被标记已读的消息 ID
 * @property chatState 正在输入状态：composing / paused / active / inactive / gone
 * @property retractId 撤回 `<retract id>`：被撤回的消息 ID
 * @property reply 引用回复
 * @property mentions @ 提醒
 * @property delayStamp `<delay stamp>` 的时间（毫秒）：离线消息的服务器时间
 * @property stanzaIds 服务器分配的 stanza-id
 * @property error 错误
 */
data class MessageStanza(
    val id: String?,
    val type: String,
    val from: String?,
    val to: String?,
    val body: String?,
    val content: MessageContent?,
    val originId: String? = null,
    val requestReceipt: Boolean = false,
    val markable: Boolean = false,
    val receivedId: String? = null,
    val displayedId: String? = null,
    val chatState: String? = null,
    val retractId: String? = null,
    val reply: ReplyRef? = null,
    val mentions: List<Mention> = emptyList(),
    val delayStamp: Long? = null,
    val stanzaIds: List<StanzaId> = emptyList(),
    val error: MessageError? = null,
) {
    /** 消息 ID：优先 message@id，没有时用 origin-id */
    val msgId: String? get() = id ?: originId
}

/** 外层包装：Carbons 抄送或 MAM 补拉结果 */
sealed interface MessageEnvelope {

    /**
     * Carbons 抄送（XEP-0280）：本人其他端发出（sent）或收到（received）的单聊消息。
     *
     * @property sent true 为其他端发出的消息，false 为其他端收到的消息
     * @property outerFrom 外层 message 的 from：必须是本人 bare JID，否则可能是伪造的抄送
     */
    data class Carbon(val sent: Boolean, val outerFrom: String?) : MessageEnvelope

    /**
     * MAM 补拉结果（XEP-0313）。
     *
     * @property queryId 查询 ID
     * @property stanzaId 存档中的 stanza-id（补拉游标）
     * @property stamp 存档时间（毫秒）
     * @property outerFrom 外层 message 的 from：查询本人归档时为本人 bare JID 或空，群归档时为群 JID
     */
    data class Mam(val queryId: String?, val stanzaId: String, val stamp: Long?, val outerFrom: String?) : MessageEnvelope
}

/**
 * 收到的一条 message 报文解析结果。
 *
 * @property envelope 外层包装；普通实时消息为 null
 * @property message 实际的消息（Carbons / MAM 时为内层消息）
 */
data class IncomingMessage(val envelope: MessageEnvelope?, val message: MessageStanza)
