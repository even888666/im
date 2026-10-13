package cn.android666.im.data.message

import androidx.room.withTransaction
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.extension.msg.Mention
import cn.android666.im.core.xmpp.extension.msg.MessageContent

/**
 * 消息仓库（Android工程架构.md §4.5）：消息写库（事务内完成去重、会话更新、未读数计算）与发送入口。
 *
 * 本类只负责数据库；真正的发送由 [SendQueue] 完成。
 *
 * @property databases 当前账号的用户数据库
 * @property sendQueue 发送队列
 * @property serverClock 服务器时间（发出消息的排序时间）
 * @property ids 消息 ID 生成器
 * @property selfJid 本人 bare JID；未登录时为 null
 */
class MessageRepository(
    private val databases: UserDatabaseHolder,
    private val sendQueue: SendQueue,
    private val serverClock: ServerClock,
    private val ids: IdGenerator,
    private val selfJid: () -> String?,
) {

    private val log = Logs.file(TAG)

    /**
     * 发送文本消息：以"发送中"写库并更新会话，然后交给发送队列（开发计划 4.3）。
     *
     * @param conversationId 会话 ID（对方 bare JID / 群 JID / 本人 bare JID）
     * @param conversationType 会话类型（ConversationEntity.TYPE_*），会话不存在时用于创建
     * @param text 文本，不能为空白，最多 [MAX_TEXT_LENGTH] 个字符（按 Unicode 码点）
     * @param replyToMsgId 引用的原消息 ID
     * @param mentions @ 提醒（群聊）
     * @return 写入的消息
     * @throws IllegalArgumentException 文本为空白或超长
     * @throws IllegalStateException 未登录
     */
    suspend fun sendText(
        conversationId: String,
        conversationType: Int,
        text: String,
        replyToMsgId: String? = null,
        mentions: List<Mention> = emptyList(),
    ): MessageEntity {
        require(text.isNotBlank()) { "消息不能为空" }
        require(text.codePointCount(0, text.length) <= MAX_TEXT_LENGTH) { "消息超过 $MAX_TEXT_LENGTH 字" }
        return enqueue(conversationId, conversationType, MessageContent.Text, text, replyToMsgId, mentions)
    }

    /**
     * 把一条发出的消息以"发送中"写库、更新会话，并唤醒发送队列。
     *
     * @param conversationId 会话 ID
     * @param conversationType 会话类型
     * @param content 内容
     * @param body 正文 / 降级文本
     * @param replyToMsgId 引用的原消息 ID
     * @param mentions @ 提醒
     * @return 写入的消息（含 rowId）
     */
    private suspend fun enqueue(
        conversationId: String,
        conversationType: Int,
        content: MessageContent,
        body: String,
        replyToMsgId: String?,
        mentions: List<Mention>,
    ): MessageEntity {
        val sender = selfJid() ?: throw IllegalStateException("未登录")
        val db = databases.require()
        val draft = MessageEntity(
            msgId = ids.messageId(),
            conversationId = conversationId,
            senderJid = sender,
            direction = MessageEntity.DIRECTION_OUT,
            type = content.type,
            body = body,
            contentJson = MessageJson.encodeContent(content),
            status = MessageEntity.STATUS_SENDING,
            serverTime = serverClock.nowMillis(),
            replyToMsgId = replyToMsgId,
            mentionsJson = MessageJson.encodeMentions(mentions),
        )
        val saved = db.withTransaction {
            val rowId = db.messageDao().insert(draft)
            check(rowId > 0) { "消息 ID 重复：${draft.msgId}" }
            val message = draft.copy(rowId = rowId)
            updateConversation(db, message, conversationType)
            message
        }
        log.info("消息已入队：msgId={}，会话={}，类型={}，长度={}", saved.msgId, conversationId, saved.type, body.length)
        sendQueue.wake()
        return saved
    }

    /**
     * 手动重发一条发送失败的消息（沿用原消息 ID，协议规范 §6.2）。
     *
     * @param rowId 消息 rowId
     * @return 是否已重新加入队列；消息不是发送失败状态时为 false
     */
    suspend fun resend(rowId: Long): Boolean {
        val updated = databases.require().messageDao().markResend(rowId) > 0
        log.info("手动重发：rowId={}，结果={}", rowId, updated)
        if (updated) sendQueue.wake()
        return updated
    }

    /**
     * 新消息写入后更新会话的最后一条消息（会话不存在时创建）。比现有最后一条更早的消息（补拉的历史）不改变预览。
     *
     * @param db 数据库（在事务中调用）
     * @param message 新消息（含 rowId）
     * @param conversationType 会话不存在时使用的类型
     */
    private suspend fun updateConversation(db: ImDatabase, message: MessageEntity, conversationType: Int) {
        val dao = db.conversationDao()
        val existing = dao.get(message.conversationId) ?: ConversationEntity(message.conversationId, conversationType)
        if (existing.lastMsgRowId != null && message.serverTime < existing.lastMsgTime) return
        dao.upsert(
            existing.copy(
                lastMsgRowId = message.rowId,
                lastMsgPreview = message.body,
                lastMsgTime = message.serverTime,
            ),
        )
    }

    companion object {
        private const val TAG = "MessageRepository"

        /** 文本消息长度上限（协议规范 §1.6，按 Unicode 码点计） */
        const val MAX_TEXT_LENGTH = 5000
    }
}
