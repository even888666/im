package cn.android666.im.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.database.entity.MessageWithSender
import kotlinx.coroutines.flow.Flow

/** 消息表的访问 */
@Dao
interface MessageDao {

    /**
     * 插入消息；"会话 + 消息 ID"已存在时忽略（协议规范 §6.3 去重）。
     *
     * @param message 消息
     * @return 新消息的 rowId；重复时为 -1
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    /**
     * 更新消息（按 rowId）。
     *
     * @param message 消息
     */
    @Update
    suspend fun update(message: MessageEntity)

    /**
     * 补上存档 ID：实时收到时没有 stanza-id 的消息，补拉时拿到存档 ID 后写入（向前翻历史时作为游标）。
     *
     * @param conversationId 会话 ID
     * @param msgId 消息 ID
     * @param stanzaId 存档 ID
     * @return 更新的条数
     */
    @Query("UPDATE message SET stanzaId = :stanzaId WHERE conversationId = :conversationId AND msgId = :msgId AND stanzaId IS NULL")
    suspend fun fillStanzaId(conversationId: String, msgId: String, stanzaId: String): Int

    /**
     * 按会话和消息 ID 查询。
     *
     * @param conversationId 会话 ID
     * @param msgId 消息 ID
     * @return 消息；不存在时为 null
     */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId AND msgId = :msgId")
    suspend fun get(conversationId: String, msgId: String): MessageEntity?

    /**
     * 按 rowId 查询。
     *
     * @param rowId rowId
     * @return 消息；不存在时为 null
     */
    @Query("SELECT * FROM message WHERE rowId = :rowId")
    suspend fun getByRowId(rowId: Long): MessageEntity?

    /**
     * 按消息 ID 查询（送达回执、发送失败等只带消息 ID 的报文，会话由调用方确定不了时使用）。
     *
     * @param msgId 消息 ID
     * @return 本人发出的该消息；不存在时为 null
     */
    @Query("SELECT * FROM message WHERE msgId = :msgId AND direction = 1 LIMIT 1")
    suspend fun getOutgoing(msgId: String): MessageEntity?

    /**
     * 修改发送状态。
     *
     * @param rowId rowId
     * @param status 新状态
     * @param errorCode 失败原因
     */
    @Query("UPDATE message SET status = :status, errorCode = :errorCode WHERE rowId = :rowId")
    suspend fun updateStatus(rowId: Long, status: Int, errorCode: String? = null)

    /**
     * 收到服务器的流管理确认：发送中 → 已发送（已被回执、已读或标记失败的不变）。
     *
     * @param rowId rowId
     * @return 更新的条数
     */
    @Query("UPDATE message SET status = 1 WHERE rowId = :rowId AND status = 0")
    suspend fun markSent(rowId: Long): Int

    /**
     * 发送失败（超时、长时间未连接等）：只修改仍在"发送中"的消息。
     *
     * @param rowId rowId
     * @param errorCode 失败原因
     * @return 更新的条数
     */
    @Query("UPDATE message SET status = 4, errorCode = :errorCode WHERE rowId = :rowId AND status = 0")
    suspend fun markSendFailed(rowId: Long, errorCode: String): Int

    /**
     * 手动重发：发送失败 → 发送中，清除失败原因（消息 ID 不变，协议规范 §6.2）。
     *
     * @param rowId rowId
     * @return 更新的条数；消息不是发送失败状态时为 0
     */
    @Query("UPDATE message SET status = 0, errorCode = NULL WHERE rowId = :rowId AND direction = 1 AND status = 4")
    suspend fun markResend(rowId: Long): Int

    /**
     * 收到送达回执：发出的消息从"发送中 / 已发送"改为"已送达"（已读的不变）。
     * 超时被标记失败、但实际已经送达的消息（服务器确认丢失）也改为"已送达"并清除失败原因（决策记录 A82）。
     *
     * @param conversationId 会话 ID
     * @param msgId 消息 ID
     * @return 更新的条数
     */
    @Query(
        "UPDATE message SET status = 2, errorCode = NULL WHERE conversationId = :conversationId AND msgId = :msgId " +
            "AND direction = 1 AND status IN (0, 1, 4)",
    )
    suspend fun markDelivered(conversationId: String, msgId: String): Int

    /**
     * 服务器退回（type='error'）：发出的消息改为"发送失败"并记录错误码（协议规范 §6.2、§6.4）。
     * 已送达、已读的不变（错误不可能晚于对方的回执，出现时以回执为准）。
     *
     * @param conversationId 会话 ID
     * @param msgId 消息 ID
     * @param errorCode 错误码：自定义错误码（如 sensitive-word），没有时为标准错误条件（如 service-unavailable）
     * @return 更新的条数
     */
    @Query(
        "UPDATE message SET status = 4, errorCode = :errorCode WHERE conversationId = :conversationId AND msgId = :msgId " +
            "AND direction = 1 AND status IN (0, 1)",
    )
    suspend fun markRejected(conversationId: String, msgId: String, errorCode: String): Int

    /**
     * 收到已读标记：该时间及之前发出的消息都改为"已读"（协议规范 §8.2）。
     *
     * @param conversationId 会话 ID
     * @param serverTime 被标记已读的消息的服务器时间
     * @return 更新的条数
     */
    @Query(
        "UPDATE message SET status = 3 WHERE conversationId = :conversationId AND direction = 1 " +
            "AND serverTime <= :serverTime AND status IN (0, 1, 2)",
    )
    suspend fun markReadUpTo(conversationId: String, serverTime: Long): Int

    /**
     * 还没发出去的消息（发送队列在启动或重连后恢复），按写入顺序。
     *
     * @return 发送中的消息
     */
    @Query("SELECT * FROM message WHERE direction = 1 AND status = 0 ORDER BY rowId")
    suspend fun pending(): List<MessageEntity>

    /**
     * 会话最近的消息（按时间倒序），聊天页第一屏、会话预览使用；分页加载见 M4 4.8。
     *
     * @param conversationId 会话 ID
     * @param limit 条数
     * @return 持续更新的消息列表（新的在前）
     */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY serverTime DESC, rowId DESC LIMIT :limit")
    fun observeLatest(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * 聊天页分页（开发计划 4.8）：会话中的消息，新的在前（列表倒序显示，最新的在底部），附带发送方姓名与头像。
     *
     * @param conversationId 会话 ID
     * @return 分页数据源（数据变化时自动失效重新加载）
     */
    @Query(
        """
        SELECT m.*, e.name AS senderName, e.avatarHash AS senderAvatarHash
        FROM message AS m LEFT JOIN employee AS e ON e.jid = m.senderJid
        WHERE m.conversationId = :conversationId
        ORDER BY m.serverTime DESC, m.rowId DESC
        """,
    )
    fun pagingSource(conversationId: String): PagingSource<Int, MessageWithSender>

    /**
     * 会话的最后一条消息（删除消息后重新计算会话预览）。
     *
     * @param conversationId 会话 ID
     * @return 最后一条消息；没有时为 null
     */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY serverTime DESC, rowId DESC LIMIT 1")
    suspend fun last(conversationId: String): MessageEntity?

    /**
     * 标记撤回（开发计划 4.12）：清空正文和内容（本地不再保留被撤回的内容），记录撤回者。
     *
     * @param rowId rowId
     * @param retractedBy 撤回者 bare JID
     */
    @Query("UPDATE message SET retracted = 1, retractedBy = :retractedBy, body = '', contentJson = NULL WHERE rowId = :rowId")
    suspend fun markRetracted(rowId: Long, retractedBy: String)

    /**
     * 撤回被服务器拒绝时恢复（本端先标记了撤回）。正文和内容已清空，由调用方传回原值。
     *
     * @param rowId rowId
     * @param body 原正文
     * @param contentJson 原内容
     */
    @Query("UPDATE message SET retracted = 0, retractedBy = NULL, body = :body, contentJson = :contentJson WHERE rowId = :rowId")
    suspend fun unmarkRetracted(rowId: Long, body: String, contentJson: String?)

    /**
     * 会话中从某时间起（含）对方发来的未撤回消息数（判断被撤回的消息是否在未读范围内）。
     *
     * @param conversationId 会话 ID
     * @param fromTime 时间（含）
     * @return 条数
     */
    @Query(
        "SELECT COUNT(*) FROM message WHERE conversationId = :conversationId AND direction = 0 " +
            "AND retracted = 0 AND serverTime >= :fromTime",
    )
    suspend fun countIncomingFrom(conversationId: String, fromTime: Long): Int

    /**
     * 会话中最早的一条消息（聊天页向服务器加载更早的历史时作为起点）。
     *
     * @param conversationId 会话 ID
     * @return 消息；没有时为 null
     */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY serverTime ASC, rowId ASC LIMIT 1")
    suspend fun earliest(conversationId: String): MessageEntity?

    /**
     * 对方发来的最新一条消息（发送已读标记，协议规范 §8.2）。
     *
     * @param conversationId 会话 ID
     * @return 消息；没有时为 null
     */
    @Query(
        "SELECT * FROM message WHERE conversationId = :conversationId AND direction = 0 " +
            "ORDER BY serverTime DESC, rowId DESC LIMIT 1",
    )
    suspend fun lastIncoming(conversationId: String): MessageEntity?

    /**
     * 会话中晚于指定时间的、对方发来的未撤回消息数（重新计算未读数）。
     *
     * @param conversationId 会话 ID
     * @param afterTime 时间（不含）
     * @return 条数
     */
    @Query(
        "SELECT COUNT(*) FROM message WHERE conversationId = :conversationId AND direction = 0 " +
            "AND retracted = 0 AND serverTime > :afterTime",
    )
    suspend fun countIncomingAfter(conversationId: String, afterTime: Long): Int

    /**
     * 删除会话的全部消息（删除会话时）。
     *
     * @param conversationId 会话 ID
     */
    @Query("DELETE FROM message WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    /**
     * 最新一条消息的服务器时间（MAM 补拉在游标缺失时按时间过滤，协议规范 §11.2）。
     *
     * @return 时间；没有消息时为 null
     */
    @Query("SELECT MAX(serverTime) FROM message")
    suspend fun maxServerTime(): Long?
}
