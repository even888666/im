package cn.android666.im.data.message

import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.extension.msg.MessageStanza

/**
 * 收到的消息报文 → 消息表记录（纯逻辑，便于单元测试）。
 */
object IncomingMessageMapper {

    /** 不认识的消息类型且没有 body 时显示的文字（协议规范 §1.5） */
    const val UNSUPPORTED_TEXT = "[当前版本不支持该消息，请升级]"

    /**
     * 去掉 resource，得到 bare JID。
     *
     * @param jid 完整 JID 或 bare JID
     * @return bare JID
     */
    fun bareJid(jid: String): String = jid.substringBefore('/')

    /**
     * 生成消息表记录。
     *
     * @param stanza 消息（必须有 content，即用户可见的消息）
     * @param conversationId 会话 ID
     * @param senderJid 发送方 bare JID
     * @param direction 方向（MessageEntity.DIRECTION_*）
     * @param status 状态（收到的为 STATUS_RECEIVED，本人其他端发出的为 STATUS_SENT）
     * @param serverTime 服务器时间（离线 / 补拉消息为 delay 时间，实时消息为本地接收时间换算的服务器时间）
     * @param selfJid 本人 bare JID（判断 stanza-id 的归属、是否 @ 了我）
     * @param fallbackId 报文没有消息 ID 时生成一个（无法去重，只为能保存）
     * @param archiveId MAM 结果中的存档 ID（stanza-id），优先于报文中的 stanza-id 元素
     * @return 消息表记录（rowId 为 0）
     */
    fun toEntity(
        stanza: MessageStanza,
        conversationId: String,
        senderJid: String,
        direction: Int,
        status: Int,
        serverTime: Long,
        selfJid: String,
        fallbackId: () -> String,
        archiveId: String? = null,
    ): MessageEntity {
        val content = stanza.content ?: MessageContent.Text
        val body = stanza.body.orEmpty().ifEmpty { if (content is MessageContent.Unknown) UNSUPPORTED_TEXT else "" }
        // 单聊的 stanza-id 由本人归档分配（by = 本人 bare JID），群聊由群分配（by = 群 JID）
        val stanzaId = archiveId ?: stanza.stanzaIds.firstOrNull { it.by == selfJid || it.by == conversationId }?.id
        return MessageEntity(
            msgId = stanza.msgId ?: stanzaId ?: fallbackId(),
            conversationId = conversationId,
            stanzaId = stanzaId,
            senderJid = senderJid,
            direction = direction,
            type = content.type,
            body = body,
            contentJson = MessageJson.encodeContent(content),
            status = status,
            serverTime = serverTime,
            replyToMsgId = stanza.reply?.id,
            mentionsJson = MessageJson.encodeMentions(stanza.mentions),
            // @ 本人，或 @ 所有人（uri 为群 JID）
            mentionMe = stanza.mentions.any { it.jid == selfJid || it.jid == conversationId },
        )
    }
}
