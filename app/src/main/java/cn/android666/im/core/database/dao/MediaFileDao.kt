package cn.android666.im.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    /**
     * 新增附件。
     *
     * @param files 附件
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<MediaFileEntity>)

    /**
     * 消息的全部附件。
     *
     * @param msgRowId 消息 rowId
     * @return 附件（缩略图在后）
     */
    @Query("SELECT * FROM media_file WHERE msgRowId = :msgRowId ORDER BY role")
    suspend fun forMessage(msgRowId: Long): List<MediaFileEntity>

    /**
     * 处于某个状态的附件所属的消息（如应用被杀时还在上传的，重新登录后继续上传）。
     *
     * @param state 状态
     * @return 消息 rowId
     */
    @Query("SELECT DISTINCT msgRowId FROM media_file WHERE state = :state ORDER BY msgRowId")
    suspend fun messagesInState(state: Int): List<Long>

    /**
     * 修改状态。
     *
     * @param id 附件 ID
     * @param state 新状态
     * @param progress 进度
     */
    @Query("UPDATE media_file SET state = :state, progress = :progress WHERE id = :id")
    suspend fun setState(id: Long, state: Int, progress: Int)

    /**
     * 修改进度。
     *
     * @param id 附件 ID
     * @param progress 进度 0 ~ 100
     */
    @Query("UPDATE media_file SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Int)

    /**
     * 上传完成：记录下载地址与本地文件。
     *
     * @param id 附件 ID
     * @param url 下载地址
     * @param localPath 本地文件
     */
    @Query("UPDATE media_file SET url = :url, localPath = :localPath, state = 2, progress = 100 WHERE id = :id")
    suspend fun markUploaded(id: Long, url: String, localPath: String)

    /**
     * 删除消息的附件记录（删除消息时）。
     *
     * @param msgRowIds 消息 rowId
     * @return 删除的行数
     */
    @Query("DELETE FROM media_file WHERE msgRowId IN (:msgRowIds)")
    suspend fun deleteForMessages(msgRowIds: List<Long>): Int
}
