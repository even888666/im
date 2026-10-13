package cn.android666.im.data.message

import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.ReplyRef

/**
 * 由消息表中的一条发出的消息生成要发送的 message 报文（发送队列在应用重启后也能从数据库恢复并重发）。
 */
object OutgoingStanzaBuilder {

    /**
     * 生成报文。
     *
     * @param message 发出的消息（会话 ID 即接收方 JID）
     * @param groupChat 是否群聊
     * @param replyToSender 被引用消息的发送方 bare JID；没有引用或本地找不到原消息时为 null（不带引用）
     * @return message 报文
     */
    fun build(message: MessageEntity, groupChat: Boolean, replyToSender: String?): String {
        val content = MessageJson.decodeContent(message.type, message.contentJson)
        val reply = message.replyToMsgId?.let { id -> replyToSender?.let { ReplyRef(it, id) } }
        return MessageCodec.chat(
            to = message.conversationId,
            id = message.msgId,
            body = message.body,
            content = content,
            groupChat = groupChat,
            reply = reply,
            mentions = MessageJson.decodeMentions(message.mentionsJson),
        )
    }
}
