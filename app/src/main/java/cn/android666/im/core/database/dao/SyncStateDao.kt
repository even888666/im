package cn.android666.im.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.android666.im.core.database.entity.SyncStateEntity

/** 同步游标的访问 */
@Dao
interface SyncStateDao {

    /**
     * 读取游标。
     *
     * @param key 游标名
     * @return 游标值；没有时为 null
     */
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    /**
     * 写入游标。
     *
     * @param state 游标
     */
    @Upsert
    suspend fun put(state: SyncStateEntity)

    /**
     * 删除游标。
     *
     * @param key 游标名
     */
    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun delete(key: String)
}
