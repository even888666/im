package cn.android666.im.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.android666.im.core.database.entity.MessageEntity
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
     * 收到送达回执：发出的消息从"发送中 / 已发送"改为"已送达"（已读、失败的不变）。
     *
     * @param conversationId 会话 ID
     * @param msgId 消息 ID
     * @return 更新的条数
     */
    @Query(
        "UPDATE message SET status = 2 WHERE conversationId = :conversationId AND msgId = :msgId " +
            "AND direction = 1 AND status IN (0, 1)",
    )
    suspend fun markDelivered(conversationId: String, msgId: String): Int

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
     * 会话的最后一条消息（删除消息后重新计算会话预览）。
     *
     * @param conversationId 会话 ID
     * @return 最后一条消息；没有时为 null
     */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY serverTime DESC, rowId DESC LIMIT 1")
    suspend fun last(conversationId: String): MessageEntity?

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
