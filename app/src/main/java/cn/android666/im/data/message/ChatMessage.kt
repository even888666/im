package cn.android666.im.data.message

import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.database.entity.MessageWithSender
import cn.android666.im.core.xmpp.extension.msg.MessageContent

/** 消息状态（界面模型） */
enum class MessageState {
    /** 发送中 */
    SENDING,

    /** 已发送（服务器已确认） */
    SENT,

    /** 已送达（对方已收到） */
    DELIVERED,

    /** 已读 */
    READ,

    /** 发送失败 */
    FAILED,

    /** 收到的消息 */
    RECEIVED,
}

/** 引用回复的原消息预览（开发计划 4.15，协议规范 §8.5） */
sealed interface ReplyPreview {

    /**
     * 原消息在本地。
     *
     * @property senderJid 原消息的发送方
     * @property senderName 原消息发送方的姓名（组织架构中有时）
     * @property text 原消息的正文 / 降级文本
     */
    data class Found(val senderJid: String, val senderName: String?, val text: String) : ReplyPreview

    /** 原消息已撤回 */
    data object Retracted : ReplyPreview

    /** 原消息不在本地（不可见） */
    data object Missing : ReplyPreview
}

/**
 * 聊天页中的一条消息（界面模型，与 Room 实体解耦）。
 *
 * @property rowId 本地 rowId（重发等操作使用）
 * @property msgId 消息 ID
 * @property conversationId 会话 ID
 * @property outgoing 是否本人发出
 * @property senderJid 发送方 bare JID
 * @property senderName 发送方姓名（组织架构中有时）
 * @property senderAvatarHash 发送方头像哈希；没有时为空
 * @property content 内容
 * @property body 正文 / 降级文本
 * @property state 状态
 * @property errorCode 发送失败原因
 * @property serverTime 服务器时间（毫秒）
 * @property replyToMsgId 引用的原消息 ID
 * @property retracted 是否已撤回
 * @property retractedBy 撤回者 bare JID
 * @property reply 引用的原消息预览；不是引用回复时为 null
 */
data class ChatMessage(
    val rowId: Long,
    val msgId: String,
    val conversationId: String,
    val outgoing: Boolean,
    val senderJid: String,
    val senderName: String?,
    val senderAvatarHash: String,
    val content: MessageContent,
    val body: String,
    val state: MessageState,
    val errorCode: String?,
    val serverTime: Long,
    val replyToMsgId: String?,
    val retracted: Boolean,
    val retractedBy: String?,
    val reply: ReplyPreview? = null,
)

/**
 * 数据库记录 → 界面模型。
 *
 * @return 聊天页消息
 */
internal fun MessageWithSender.toChatMessage(): ChatMessage {
    val m = message
    return ChatMessage(
        rowId = m.rowId,
        msgId = m.msgId,
        conversationId = m.conversationId,
        outgoing = m.direction == MessageEntity.DIRECTION_OUT,
        senderJid = m.senderJid,
        senderName = senderName,
        senderAvatarHash = senderAvatarHash.orEmpty(),
        content = MessageJson.decodeContent(m.type, m.contentJson),
        body = m.body,
        state = when (m.status) {
            MessageEntity.STATUS_SENDING -> MessageState.SENDING
            MessageEntity.STATUS_SENT -> MessageState.SENT
            MessageEntity.STATUS_DELIVERED -> MessageState.DELIVERED
            MessageEntity.STATUS_READ -> MessageState.READ
            MessageEntity.STATUS_FAILED -> MessageState.FAILED
            else -> MessageState.RECEIVED
        },
        errorCode = m.errorCode,
        serverTime = m.serverTime,
        replyToMsgId = m.replyToMsgId,
        retracted = m.retracted,
        retractedBy = m.retractedBy,
        reply = when {
            m.replyToMsgId == null -> null
            replyRowId == null -> ReplyPreview.Missing
            replyRetracted == true -> ReplyPreview.Retracted
            else -> ReplyPreview.Found(replySenderJid.orEmpty(), replySenderName, replyBody.orEmpty())
        },
    )
}

/**
 * 是否可以撤回（菜单中显示"撤回"）：本人发出、已经发出（不是发送中 / 失败）、未撤回、在时限内。
 * 服务器还会再校验一次（biz 插件，开发计划 4.20）。
 *
 * @param now 当前服务器时间（毫秒）
 * @return 可以撤回时为 true
 */
fun ChatMessage.canRetract(now: Long): Boolean =
    outgoing && !retracted && state != MessageState.SENDING && state != MessageState.FAILED &&
        now - serverTime <= Retractions.TIME_LIMIT_MILLIS

