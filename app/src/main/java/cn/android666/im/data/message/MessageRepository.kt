package cn.android666.im.data.message

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.RemoteMediator
import androidx.paging.map
import androidx.room.withTransaction
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.database.entity.MessageWithSender
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.extension.msg.Mention
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

    /** 用户正在查看的会话（聊天页可见时设置），该会话的新消息不计未读 */
    private val activeConversation = MutableStateFlow<String?>(null)

    /**
     * 当前服务器时间（界面判断能否撤回等）。
     *
     * @return 毫秒
     */
    fun serverNowMillis(): Long = serverClock.nowMillis()

    /**
     * 设置用户正在查看的会话。
     *
     * @param conversationId 会话 ID；离开聊天页时为 null
     */
    fun setActiveConversation(conversationId: String?) {
        activeConversation.value = conversationId
        log.info("当前查看的会话：{}", conversationId)
    }

    /**
     * 用户正在查看的会话。
     *
     * @return 会话 ID；没有打开聊天页时为 null
     */
    fun activeConversationId(): String? = activeConversation.value

    /**
     * 聊天页的分页消息（开发计划 4.8）：新的在前，数据库变化时自动刷新。未登录时为空。
     *
     * @param conversationId 会话 ID
     * @param remote 本地消息翻到底后从服务器加载更早历史的加载器（开发计划 4.11）；null 表示只显示本地消息
     * @return 分页数据
     */
    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalPagingApi::class)
    fun pagedMessages(
        conversationId: String,
        remote: RemoteMediator<Int, MessageWithSender>? = null,
    ): Flow<PagingData<ChatMessage>> = databases.database.flatMapLatest { db ->
        if (db == null) {
            flowOf(PagingData.empty())
        } else {
            Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false), remoteMediator = remote) {
                db.messageDao().pagingSource(conversationId)
            }
                .flow
                .map { page -> page.map { it.toChatMessage() } }
        }
    }

    /**
     * 保存一条收到的消息（开发计划 4.4，Android工程架构.md §6.1）：事务内去重 → 插入 → 更新会话与未读数。
     *
     * @param message 消息（rowId 为 0）
     * @param conversationType 会话不存在时使用的类型
     * @param countUnread 是否计入未读（首次登录导入的历史消息不计）
     * @return 保存后的消息（含 rowId）；"会话 + 消息 ID"已存在（重复）时为 null
     * @throws IllegalStateException 未登录
     */
    suspend fun saveIncoming(message: MessageEntity, conversationType: Int, countUnread: Boolean = true): MessageEntity? {
        val db = databases.require()
        return db.withTransaction {
            val rowId = db.messageDao().insert(message)
            if (rowId <= 0) {
                // 重复的消息：补拉时带来了存档 ID，补上（向前翻历史时作为游标）
                message.stanzaId?.let { db.messageDao().fillStanzaId(message.conversationId, message.msgId, it) }
                return@withTransaction null
            }
            val saved = message.copy(rowId = rowId)
            updateConversation(db, saved, conversationType, countUnread)
            saved
        }
    }

    /**
     * 收到送达回执（开发计划 4.5，协议规范 §8.1）。
     *
     * @param conversationId 会话 ID（回执发送方的 bare JID）
     * @param msgId 被确认的消息 ID
     * @return 是否更新了消息（找不到、已读等情况为 false）
     */
    suspend fun markDelivered(conversationId: String, msgId: String): Boolean =
        databases.require().messageDao().markDelivered(conversationId, msgId) > 0

    /**
     * 对方已读（开发计划 4.9，协议规范 §8.2）：被标记的消息及之前本人发出的消息都改为"已读"。
     *
     * @param conversationId 会话 ID（对方 bare JID）
     * @param msgId 被标记已读的消息 ID（本人发出的）
     * @return 更新的条数；找不到该消息时为 0
     */
    suspend fun markReadByPeer(conversationId: String, msgId: String): Int {
        val dao = databases.require().messageDao()
        val message = dao.get(conversationId, msgId)?.takeIf { it.direction == MessageEntity.DIRECTION_OUT } ?: return 0
        return dao.markReadUpTo(conversationId, message.serverTime)
    }

    /**
     * 本人其他端已读到某条消息（开发计划 4.9，多端已读同步）：未读数改为该消息之后对方发来的消息数。
     *
     * @param conversationId 会话 ID
     * @param msgId 其他端标记已读的消息 ID（对方发来的）
     * @return 新的未读数；找不到该消息时为 null（不修改）
     */
    suspend fun applyReadElsewhere(conversationId: String, msgId: String): Int? {
        val db = databases.require()
        return db.withTransaction {
            val message = db.messageDao().get(conversationId, msgId) ?: return@withTransaction null
            val remaining = db.messageDao().countIncomingAfter(conversationId, message.serverTime)
            db.conversationDao().setUnread(conversationId, remaining)
            remaining
        }
    }

    /**
     * 应用撤回（开发计划 4.12，协议规范 §8.4）：原消息标为已撤回并清空内容；原消息还没收到时插入一条已撤回的占位记录，
     * 防止之后补拉到原消息时又显示出来。被撤回的消息在未读范围内时未读数减 1；是会话最后一条时预览改为撤回提示。
     *
     * @param conversationId 会话 ID
     * @param msgId 被撤回的消息 ID
     * @param retractedBy 撤回者 bare JID
     * @param direction 原消息的方向（插入占位记录时使用：撤回者是本人则为发出）
     * @param time 撤回报文的服务器时间（占位记录的时间）
     * @param notice 撤回提示文字（会话预览），如"张三撤回了一条消息"
     * @param conversationType 会话不存在时使用的类型
     * @return 被撤回的消息（含 rowId）；之前已经撤回过时为 null
     */
    suspend fun applyRetract(
        conversationId: String,
        msgId: String,
        retractedBy: String,
        direction: Int,
        time: Long,
        notice: String,
        conversationType: Int,
    ): MessageEntity? {
        val db = databases.require()
        return db.withTransaction {
            val dao = db.messageDao()
            val existing = dao.get(conversationId, msgId)
            if (existing?.retracted == true) return@withTransaction null
            val message = if (existing == null) {
                // 先收到撤回：插入占位记录（不计未读）
                val placeholder = MessageEntity(
                    msgId = msgId,
                    conversationId = conversationId,
                    senderJid = if (direction == MessageEntity.DIRECTION_OUT) retractedBy else conversationId,
                    direction = direction,
                    type = MessageContent.Text.type,
                    body = "",
                    status = if (direction == MessageEntity.DIRECTION_OUT) MessageEntity.STATUS_SENT else MessageEntity.STATUS_RECEIVED,
                    serverTime = time,
                    retracted = true,
                    retractedBy = retractedBy,
                )
                placeholder.copy(rowId = dao.insert(placeholder))
            } else {
                // 被撤回的是未读消息：未读减 1
                val conversation = db.conversationDao().get(conversationId)
                if (existing.direction == MessageEntity.DIRECTION_IN && conversation != null && conversation.unreadCount > 0 &&
                    dao.countIncomingFrom(conversationId, existing.serverTime) <= conversation.unreadCount
                ) {
                    db.conversationDao().setUnread(conversationId, conversation.unreadCount - 1)
                }
                dao.markRetracted(existing.rowId, retractedBy)
                existing.copy(retracted = true, retractedBy = retractedBy, body = "", contentJson = null)
            }
            // 预览：被撤回的是最后一条（或会话还没有消息）时显示撤回提示
            val conversationDao = db.conversationDao()
            val conversation = conversationDao.get(conversationId) ?: ConversationEntity(conversationId, conversationType)
            val isLast = conversation.lastMsgRowId == null || conversation.lastMsgRowId == message.rowId ||
                (existing == null && message.serverTime > conversation.lastMsgTime)
            if (isLast) {
                conversationDao.upsert(
                    conversation.copy(lastMsgRowId = message.rowId, lastMsgPreview = notice, lastMsgTime = maxOf(message.serverTime, conversation.lastMsgTime)),
                )
            }
            message
        }
    }

    /**
     * 撤回被服务器拒绝：恢复原消息。
     *
     * @param original 撤回前的消息
     */
    suspend fun revertRetract(original: MessageEntity) {
        val db = databases.require()
        db.withTransaction {
            db.messageDao().unmarkRetracted(original.rowId, original.body, original.contentJson)
            val conversation = db.conversationDao().get(original.conversationId)
            if (conversation?.lastMsgRowId == original.rowId) {
                db.conversationDao().upsert(conversation.copy(lastMsgPreview = original.body))
            }
        }
    }

    /**
     * 删除本地消息（开发计划 4.17，只删除本机，服务器存档和对方不受影响）。被删除的是会话最后一条时，
     * 预览改为剩下的最后一条（没有剩余消息时清空）。
     *
     * @param rowIds 消息 rowId（最多 [MAX_SELECTION] 条）
     * @return 删除的条数
     */
    suspend fun deleteLocal(rowIds: Collection<Long>): Int {
        if (rowIds.isEmpty()) return 0
        val ids = rowIds.take(MAX_SELECTION)
        val db = databases.require()
        return db.withTransaction {
            val dao = db.messageDao()
            val conversationIds = dao.getByRowIds(ids).map { it.conversationId }.toSet()
            val deleted = dao.deleteByRowIds(ids)
            for (id in conversationIds) {
                val conversation = db.conversationDao().get(id) ?: continue
                if (conversation.lastMsgRowId == null || conversation.lastMsgRowId !in ids) continue
                val last = dao.last(id)
                db.conversationDao().upsert(
                    conversation.copy(
                        lastMsgRowId = last?.rowId,
                        lastMsgPreview = last?.let(::previewOf).orEmpty(),
                        lastMsgTime = last?.serverTime ?: conversation.lastMsgTime,
                    ),
                )
            }
            log.info("本地删除消息 {} 条", deleted)
            deleted
        }
    }

    /**
     * 消息在会话列表中的预览：已撤回的显示撤回提示，其余为正文 / 降级文本。
     *
     * @param message 消息
     * @return 预览文字
     */
    private fun previewOf(message: MessageEntity): String = when {
        !message.retracted -> message.body
        message.direction == MessageEntity.DIRECTION_OUT -> Retractions.SELF_NOTICE
        else -> Retractions.PEER_NOTICE
    }

    /**
     * 按会话和消息 ID 查询消息。
     *
     * @param conversationId 会话 ID
     * @param msgId 消息 ID
     * @return 消息；不存在时为 null
     */
    suspend fun find(conversationId: String, msgId: String): MessageEntity? = databases.require().messageDao().get(conversationId, msgId)

    /**
     * 按 rowId 查询消息。
     *
     * @param rowId rowId
     * @return 消息；不存在时为 null
     */
    suspend fun get(rowId: Long): MessageEntity? = databases.require().messageDao().getByRowId(rowId)

    /**
     * 按 rowId 查询消息（界面模型，不含发送方姓名和引用信息）。
     *
     * @param rowId rowId
     * @return 消息；不存在时为 null
     */
    suspend fun chatMessage(rowId: Long): ChatMessage? = get(rowId)?.let { MessageWithSender(it, null, null).toChatMessage() }

    /**
     * 会话中对方发来的最新一条消息（发送已读标记）。
     *
     * @param conversationId 会话 ID
     * @return 消息；没有时为 null
     */
    suspend fun lastIncoming(conversationId: String): MessageEntity? = databases.require().messageDao().lastIncoming(conversationId)

    /**
     * 服务器退回了发出的消息（开发计划 4.5，协议规范 §6.4）：标记发送失败。
     *
     * @param conversationId 会话 ID（错误报文的 from 的 bare JID，即原接收方）
     * @param msgId 原消息 ID
     * @param errorCode 错误码
     * @return 是否更新了消息
     */
    suspend fun markRejected(conversationId: String, msgId: String, errorCode: String): Boolean =
        databases.require().messageDao().markRejected(conversationId, msgId, errorCode) > 0

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
     * 新消息写入后更新会话（会话不存在时创建）：
     * - 最后一条消息：比现有最后一条更早的消息（补拉的历史、迟到的离线消息）不改变预览；
     * - 未读数：对方发来的、未撤回的、且用户没有正在查看该会话时加 1；@ 了我时标记"有人@我"。
     *
     * @param db 数据库（在事务中调用）
     * @param message 新消息（含 rowId）
     * @param conversationType 会话不存在时使用的类型
     * @param allowUnread 是否允许计入未读（首次登录导入的历史消息为 false）
     */
    private suspend fun updateConversation(db: ImDatabase, message: MessageEntity, conversationType: Int, allowUnread: Boolean = true) {
        val dao = db.conversationDao()
        val stored = dao.get(message.conversationId)
        val existing = stored ?: ConversationEntity(message.conversationId, conversationType)
        val countUnread = allowUnread && message.direction == MessageEntity.DIRECTION_IN && !message.retracted &&
            activeConversation.value != message.conversationId
        val isLatest = existing.lastMsgRowId == null || message.serverTime >= existing.lastMsgTime
        var updated = existing
        if (isLatest) {
            updated = updated.copy(lastMsgRowId = message.rowId, lastMsgPreview = message.body, lastMsgTime = message.serverTime)
        }
        if (countUnread) {
            updated = updated.copy(unreadCount = updated.unreadCount + 1, mentionMe = updated.mentionMe || message.mentionMe)
        }
        if (stored == null || updated != existing) dao.upsert(updated)
    }

    companion object {
        private const val TAG = "MessageRepository"

        /** 文本消息长度上限（协议规范 §1.6，按 Unicode 码点计） */
        const val MAX_TEXT_LENGTH = 5000

        /** 多选操作（删除、转发）一次最多处理的消息数（协议规范 §1.6：合并转发最多 100 条） */
        const val MAX_SELECTION = 100

        /** 聊天页每页加载的消息数 */
        private const val PAGE_SIZE = 30
    }
}
