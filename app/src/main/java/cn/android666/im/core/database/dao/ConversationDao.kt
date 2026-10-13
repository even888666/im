package cn.android666.im.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.android666.im.core.database.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/** 会话表的访问 */
@Dao
interface ConversationDao {

    /**
     * 新增或更新会话。
     *
     * @param conversation 会话
     */
    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    /**
     * 查询会话。
     *
     * @param id 会话 ID
     * @return 会话；不存在时为 null
     */
    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    /**
     * 观察一个会话（聊天页标题、免打扰状态）。
     *
     * @param id 会话 ID
     * @return 会话；不存在时为 null
     */
    @Query("SELECT * FROM conversation WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    /**
     * 会话列表：置顶的在前（按置顶时间倒序），其余按最后一条消息时间倒序；没有消息也没有草稿的会话不显示。
     *
     * @return 持续更新的会话列表
     */
    @Query(
        "SELECT * FROM conversation WHERE lastMsgRowId IS NOT NULL OR draft != '' OR pinned = 1 " +
            "ORDER BY pinned DESC, pinnedAt DESC, lastMsgTime DESC",
    )
    fun observeAll(): Flow<List<ConversationEntity>>

    /**
     * 总未读数（底部"消息" Tab 的角标），免打扰的会话不计入。
     *
     * @return 持续更新的未读数
     */
    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM conversation WHERE muted = 0")
    fun observeTotalUnread(): Flow<Int>

    /**
     * 清除未读（打开会话、其他端已读）。
     *
     * @param id 会话 ID
     */
    @Query("UPDATE conversation SET unreadCount = 0, mentionMe = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    /**
     * 保存草稿。
     *
     * @param id 会话 ID
     * @param draft 草稿
     */
    @Query("UPDATE conversation SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: String, draft: String)

    /**
     * 删除会话。
     *
     * @param id 会话 ID
     */
    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: String)
}
