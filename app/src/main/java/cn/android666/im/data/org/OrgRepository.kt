package cn.android666.im.data.org

import androidx.room.withTransaction
import cn.android666.im.core.common.Clock
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.dao.EmployeeDao
import cn.android666.im.core.database.entity.DepartmentEntity
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.core.database.entity.SyncStateEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.org.OrgSyncEntry
import cn.android666.im.core.xmpp.extension.org.OrgSyncRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一次组织架构同步的结果。
 *
 * @property version 同步后的版本号
 * @property full 是否为全量同步
 * @property pages 拉取的页数（含最后的空页）
 * @property upserts 新增或更新的部门 + 员工数
 * @property deletes 删除的部门 + 员工数
 */
data class OrgSyncResult(val version: Long, val full: Boolean, val pages: Int, val upserts: Int, val deletes: Int)

/**
 * 组织架构（开发计划 3.14、3.15，协议规范 §4）：全量缓存 + 增量同步，查询和搜索都在本地完成。
 *
 * 同步（§4.4）：
 * - 用本地版本号请求，按 RSM 分页拉完所有页面后，在**一个事务**中写入：全量时先清空，再 upsert / delete，最后保存版本号；
 *   中途失败不写入任何内容，下次从旧版本重新同步；
 * - 保存第一页的版本号（分页期间服务器有变更时，后面页面的版本号可能更大，见协议规范 §4.4）；
 * - 触发时机：登录后（[OrgSyncStep]）、回到前台且距上次同步超过 10 分钟（[syncIfStale]）、用户下拉刷新。
 *
 * @property client XMPP 客户端
 * @property databases 当前账号的用户数据库
 * @property clock 时钟（计算距上次同步的时间）
 */
class OrgRepository(
    private val client: XmppClient,
    private val databases: UserDatabaseHolder,
    private val clock: Clock,
) {

    private val log = Logs.file(TAG)

    /** 同步互斥：同一时刻只有一次同步 */
    private val syncMutex = Mutex()

    private val mutableSyncing = MutableStateFlow(false)

    /** 是否正在同步（通讯录下拉刷新的进度） */
    val syncing: StateFlow<Boolean> = mutableSyncing.asStateFlow()

    /** 上次同步成功的单调时间（毫秒）；本进程内还没有同步过时为 null */
    @Volatile
    private var lastSyncElapsed: Long? = null

    /**
     * 同步组织架构。已有同步在进行时等它结束后再执行一次（保证调用返回时数据是最新的）。
     *
     * @return 同步结果
     * @throws XmppException 网络或服务器错误（不写入任何数据）
     * @throws IllegalStateException 未登录（用户数据库未打开）
     */
    suspend fun sync(): OrgSyncResult = syncMutex.withLock {
        mutableSyncing.value = true
        try {
            doSync().also { lastSyncElapsed = clock.elapsedMillis() }
        } finally {
            mutableSyncing.value = false
        }
    }

    /**
     * 距上次同步超过 [maxAgeMillis] 时同步（回到前台时调用）；失败只记录日志。
     *
     * @param maxAgeMillis 允许的最长间隔，默认 10 分钟（协议规范 §4.4）
     * @return 是否执行了同步并成功
     */
    suspend fun syncIfStale(maxAgeMillis: Long = STALE_AFTER_MILLIS): Boolean {
        val last = lastSyncElapsed
        if (last != null && clock.elapsedMillis() - last < maxAgeMillis) return false
        if (databases.database.value == null) return false
        return try {
            sync()
            true
        } catch (e: XmppException) {
            log.warn("回到前台时同步组织架构失败：{}", e.message)
            false
        }
    }

    /**
     * 执行一次同步。
     *
     * @return 同步结果
     */
    private suspend fun doSync(): OrgSyncResult {
        val db = databases.require()
        val localVersion = db.syncStateDao().get(KEY_VERSION)?.toLongOrNull() ?: 0L
        log.info("开始同步组织架构：本地版本 {}", localVersion)

        val entries = mutableListOf<OrgSyncEntry>()
        var after: String? = null
        var version: Long? = null
        var full = false
        var pages = 0
        while (true) {
            check(pages < MAX_PAGES) { "组织架构同步超过 $MAX_PAGES 页，可能是分页游标没有前进" }
            val page = client.sendIq(OrgSyncRequest(version = localVersion, max = PAGE_SIZE, after = after))
            pages++
            if (version == null) {
                version = page.version
                full = page.full
            }
            if (page.entries.isEmpty()) break
            entries += page.entries
            // 没有游标时无法继续分页，视为最后一页
            after = page.rsm?.last ?: break
        }

        val departments = entries.filterIsInstance<OrgSyncEntry.DepartmentUpsert>().map { it.department.toEntity() }
        val employees = entries.filterIsInstance<OrgSyncEntry.EmployeeUpsert>().map { it.employee.toEntity() }
        val deletedDepartments = entries.filterIsInstance<OrgSyncEntry.DepartmentDelete>().map { it.id }
        val deletedEmployees = entries.filterIsInstance<OrgSyncEntry.EmployeeDelete>().map { it.jid }
        val newVersion = checkNotNull(version)
        db.withTransaction {
            if (full) {
                db.departmentDao().clear()
                db.employeeDao().clear()
            }
            departments.chunked(EmployeeDao.MAX_BIND_ARGS).forEach { db.departmentDao().upsert(it) }
            employees.chunked(EmployeeDao.MAX_BIND_ARGS).forEach { db.employeeDao().upsert(it) }
            deletedDepartments.chunked(EmployeeDao.MAX_BIND_ARGS).forEach { db.departmentDao().delete(it) }
            deletedEmployees.chunked(EmployeeDao.MAX_BIND_ARGS).forEach { db.employeeDao().delete(it) }
            db.syncStateDao().put(SyncStateEntity(KEY_VERSION, newVersion.toString()))
        }
        val result = OrgSyncResult(
            version = newVersion,
            full = full,
            pages = pages,
            upserts = departments.size + employees.size,
            deletes = deletedDepartments.size + deletedEmployees.size,
        )
        log.info("组织架构同步完成：{}", result)
        return result
    }

    // ---------------------------------------------------------------- 查询（通讯录界面）

    /**
     * 下级部门（按排序号、名称）。未登录时为空。
     *
     * @param parentId 上级部门 ID，空字符串表示顶级部门
     * @return 持续更新的部门列表
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeChildren(parentId: String): Flow<List<Department>> = databases.database.flatMapLatest { db ->
        db?.departmentDao()?.observeChildren(parentId)?.map { list -> list.map(DepartmentEntity::toModel) } ?: flowOf(emptyList())
    }

    /**
     * 部门的直属员工（按排序号、拼音）。未登录时为空。
     *
     * @param deptId 部门 ID
     * @return 持续更新的员工列表
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMembers(deptId: String): Flow<List<Employee>> = databases.database.flatMapLatest { db ->
        db?.employeeDao()?.observeByDepartment(deptId)?.map { list -> list.map(EmployeeEntity::toModel) } ?: flowOf(emptyList())
    }

    /**
     * 观察一名员工（资料卡、聊天页标题）。
     *
     * @param jid bare JID
     * @return 员工；不在缓存中（未同步或已离职）时为 null
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeEmployee(jid: String): Flow<Employee?> = databases.database.flatMapLatest { db ->
        db?.employeeDao()?.observe(jid)?.map { it?.toModel() } ?: emptyFlow()
    }

    /**
     * 查询一名员工。
     *
     * @param jid bare JID
     * @return 员工；不在缓存中时为 null
     */
    suspend fun employee(jid: String): Employee? = databases.database.value?.employeeDao()?.get(jid)?.toModel()

    /**
     * 部门的完整路径（如 总部/研发中心/Android组），用于资料卡和搜索结果。
     *
     * @param deptId 部门 ID
     * @return 路径；部门不在缓存中时为空字符串
     */
    suspend fun departmentPath(deptId: String): String {
        val db = databases.database.value ?: return ""
        val names = mutableListOf<String>()
        var id = deptId
        // 最多 50 级，防止数据异常时死循环
        repeat(50) {
            val dept = db.departmentDao().get(id) ?: return names.reversed().joinToString("/")
            names += dept.name
            if (dept.parentId.isEmpty()) return names.reversed().joinToString("/")
            id = dept.parentId
        }
        return names.reversed().joinToString("/")
    }

    /**
     * 本地搜索同事（开发计划 3.15，协议规范 §4.5 的规则）：匹配姓名、全拼、首字母、工号、手机、部门路径，按匹配度排序。
     *
     * @param keyword 关键字
     * @param limit 最多返回的条数
     * @return 员工列表，匹配度高的在前；未登录或关键字为空时为空列表
     */
    suspend fun search(keyword: String, limit: Int = SEARCH_LIMIT): List<Employee> {
        val kw = keyword.trim().lowercase()
        val db = databases.database.value
        if (kw.isEmpty() || db == null) return emptyList()

        // 部门路径：部门数量远少于员工，全部读出后在内存中计算
        val departments = db.departmentDao().all().associateBy { it.id }
        val paths = HashMap<String, String>(departments.size)
        fun pathOf(id: String): String = paths.getOrPut(id) {
            val names = mutableListOf<String>()
            var current = departments[id]
            var depth = 0
            while (current != null && depth++ < 50) {
                names += current.name
                current = if (current.parentId.isEmpty()) null else departments[current.parentId]
            }
            names.reversed().joinToString("/")
        }
        val matchedDepts = departments.keys.filter { pathOf(it).lowercase().contains(kw) }

        val candidates = LinkedHashMap<String, EmployeeEntity>()
        db.employeeDao().searchCandidates(escapeLike(kw)).forEach { candidates[it.jid] = it }
        matchedDepts.chunked(EmployeeDao.MAX_BIND_ARGS).forEach { chunk ->
            db.employeeDao().getByDepartments(chunk).forEach { candidates[it.jid] = it }
        }
        return candidates.values
            .map { it to SearchScorer.score(kw, it, pathOf(it.deptId)) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<EmployeeEntity, Int>> { it.second }.thenBy { it.first.pinyin }.thenBy { it.first.empNo })
            .take(limit)
            .map { it.first.toModel() }
    }

    companion object {
        private const val TAG = "OrgRepository"

        /** sync_state 中保存组织架构版本号的键 */
        const val KEY_VERSION = "org:version"

        /** 每页条数（协议规范 §1.6 上限 500） */
        const val PAGE_SIZE = 500

        /** 页数上限（50 万条），防止游标异常时无限请求 */
        const val MAX_PAGES = 1000

        /** 回到前台时自动同步的间隔：10 分钟（协议规范 §4.4） */
        const val STALE_AFTER_MILLIS = 10 * 60 * 1000L

        /** 搜索结果的默认条数 */
        const val SEARCH_LIMIT = 50

        /**
         * 转义 LIKE 的通配符（转义字符为 \，与 [EmployeeDao.searchCandidates] 的 ESCAPE 一致）。
         *
         * @param text 关键字
         * @return 转义后的关键字
         */
        internal fun escapeLike(text: String): String =
            text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
