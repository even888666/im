package cn.android666.im.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.android666.im.core.database.entity.MediaFileEntity
import kotlinx.coroutines.flow.Flow

/** 附件本地状态的访问（M5 媒体消息使用） */
@Dao
interface MediaFileDao {

    /**
     * 新增或更新附件状态。
     *
     * @param file 附件
     */
    @Upsert
    suspend fun upsert(file: MediaFileEntity)

    /**
     * 消息的附件。
     *
     * @param msgRowId 消息 rowId
     * @param role 角色（原文件 / 缩略图）
     * @return 附件；没有时为 null
     */
    @Query("SELECT * FROM media_file WHERE msgRowId = :msgRowId AND role = :role")
    suspend fun get(msgRowId: Long, role: Int): MediaFileEntity?

    /**
     * 观察消息的全部附件（下载进度）。
     *
     * @param msgRowId 消息 rowId
     * @return 附件列表
     */
    @Query("SELECT * FROM media_file WHERE msgRowId = :msgRowId")
    fun observe(msgRowId: Long): Flow<List<MediaFileEntity>>
}
