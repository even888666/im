package cn.android666.im.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.android666.im.core.database.entity.DepartmentEntity
import kotlinx.coroutines.flow.Flow

/** 部门表的访问 */
@Dao
interface DepartmentDao {

    /**
     * 新增或更新部门。
     *
     * @param departments 部门
     */
    @Upsert
    suspend fun upsert(departments: List<DepartmentEntity>)

    /**
     * 删除部门。
     *
     * @param ids 部门 ID
     */
    @Query("DELETE FROM department WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    /** 清空（全量同步前） */
    @Query("DELETE FROM department")
    suspend fun clear()

    /**
     * 查询部门。
     *
     * @param id 部门 ID
     * @return 部门；不存在时为 null
     */
    @Query("SELECT * FROM department WHERE id = :id")
    suspend fun get(id: String): DepartmentEntity?

    /**
     * 全部部门（本地搜索计算部门路径用；部门数量远少于员工）。
     *
     * @return 部门列表
     */
    @Query("SELECT * FROM department")
    suspend fun all(): List<DepartmentEntity>

    /**
     * 下级部门，按排序号、名称排列。
     *
     * @param parentId 上级部门 ID，空字符串表示顶级部门
     * @return 持续更新的下级部门列表
     */
    @Query("SELECT * FROM department WHERE parentId = :parentId ORDER BY sort, name")
    fun observeChildren(parentId: String): Flow<List<DepartmentEntity>>

    /**
     * 部门数量。
     *
     * @return 数量
     */
    @Query("SELECT COUNT(*) FROM department")
    suspend fun count(): Int
}
