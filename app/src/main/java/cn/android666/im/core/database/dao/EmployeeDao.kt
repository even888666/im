package cn.android666.im.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.android666.im.core.database.entity.EmployeeEntity
import kotlinx.coroutines.flow.Flow

/** 员工表的访问 */
@Dao
interface EmployeeDao {

    /**
     * 新增或更新员工。
     *
     * @param employees 员工
     */
    @Upsert
    suspend fun upsert(employees: List<EmployeeEntity>)

    /**
     * 删除员工（离职 / 禁用）。
     *
     * @param jids bare JID
     */
    @Query("DELETE FROM employee WHERE jid IN (:jids)")
    suspend fun delete(jids: List<String>)

    /** 清空（全量同步前） */
    @Query("DELETE FROM employee")
    suspend fun clear()

    /**
     * 查询员工。
     *
     * @param jid bare JID
     * @return 员工；不在缓存中（未同步或已离职）时为 null
     */
    @Query("SELECT * FROM employee WHERE jid = :jid")
    suspend fun get(jid: String): EmployeeEntity?

    /**
     * 查询员工并持续观察变化（资料卡、聊天页标题）。
     *
     * @param jid bare JID
     * @return 员工；不在缓存中时为 null
     */
    @Query("SELECT * FROM employee WHERE jid = :jid")
    fun observe(jid: String): Flow<EmployeeEntity?>

    /**
     * 批量查询（会话列表、群成员补全姓名）。
     *
     * @param jids bare JID
     * @return 缓存中有的员工
     */
    @Query("SELECT * FROM employee WHERE jid IN (:jids)")
    suspend fun getAll(jids: List<String>): List<EmployeeEntity>

    /**
     * 部门的直属员工，按排序号、拼音排列。
     *
     * @param deptId 部门 ID
     * @return 持续更新的员工列表
     */
    @Query("SELECT * FROM employee WHERE deptId = :deptId ORDER BY sort, pinyin, empNo")
    fun observeByDepartment(deptId: String): Flow<List<EmployeeEntity>>

    /**
     * 本地搜索的候选员工：姓名 / 手机包含、拼音 / 首字母 / 工号开头。部门路径匹配的员工用 [getByDepartments] 另外查询。
     * 结果再由 data/org 中的打分规则（协议规范 §4.5）排序。
     *
     * @param keyword 关键字（已转小写；LIKE 的通配符已转义，转义字符为 \）
     * @return 候选员工
     */
    @Query(
        """
        SELECT * FROM employee
        WHERE name LIKE '%' || :keyword || '%' ESCAPE '\'
           OR pinyin LIKE :keyword || '%' ESCAPE '\'
           OR initials LIKE :keyword || '%' ESCAPE '\'
           OR empNo LIKE :keyword || '%' ESCAPE '\'
           OR mobile LIKE '%' || :keyword || '%' ESCAPE '\'
        """,
    )
    suspend fun searchCandidates(keyword: String): List<EmployeeEntity>

    /**
     * 多个部门的直属员工（本地搜索中部门路径匹配的部门）。调用方需按 [MAX_BIND_ARGS] 分批，
     * Android 7 自带的 SQLite 每条语句最多 999 个参数。
     *
     * @param deptIds 部门 ID
     * @return 员工
     */
    @Query("SELECT * FROM employee WHERE deptId IN (:deptIds)")
    suspend fun getByDepartments(deptIds: List<String>): List<EmployeeEntity>

    /**
     * 员工数量。
     *
     * @return 数量
     */
    @Query("SELECT COUNT(*) FROM employee")
    suspend fun count(): Int

    companion object {
        /** 一条语句中 IN 列表的参数上限（低于 SQLite 3.32 之前的 999 限制，留出余量） */
        const val MAX_BIND_ARGS = 500
    }
}
