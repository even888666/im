package cn.android666.im.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.core.database.DbFixtures.dept
import cn.android666.im.core.database.DbFixtures.emp
import cn.android666.im.core.database.entity.SyncStateEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 用户数据库的 DAO 测试（Robolectric，内存数据库） */
@RunWith(RobolectricTestRunner::class)
class ImDatabaseTest {

    private lateinit var db: ImDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun departments_upsertChildrenOrderDelete() = runTest {
        val dao = db.departmentDao()
        dao.upsert(listOf(dept("d1", "", "总部"), dept("d3", "d1", "市场部", 20), dept("d2", "d1", "研发中心", 10)))
        dao.upsert(listOf(dept("d3", "d1", "市场营销部", 20)))

        dao.observeChildren("d1").test {
            assertThat(awaitItem().map { it.name }).containsExactly("研发中心", "市场营销部").inOrder()
            dao.delete(listOf("d2"))
            assertThat(awaitItem().map { it.name }).containsExactly("市场营销部")
        }
        assertThat(dao.count()).isEqualTo(2)
        dao.clear()
        assertThat(dao.all()).isEmpty()
    }

    @Test
    fun employees_byDepartmentOrdered_getDelete() = runTest {
        val dao = db.employeeDao()
        dao.upsert(
            listOf(
                emp("10001", "张三", "zhangsan", "zs", "d2"),
                emp("10002", "李四", "lisi", "ls", "d2"),
                emp("10003", "王五", "wangwu", "ww", "d2", sort = -1),
                emp("10004", "赵六", "zhaoliu", "zl", "d3"),
            ),
        )
        dao.observeByDepartment("d2").test {
            // 先按 sort，再按拼音
            assertThat(awaitItem().map { it.empNo }).containsExactly("10003", "10002", "10001").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(dao.get("10001@im.example.com")?.name).isEqualTo("张三")
        assertThat(dao.getAll(listOf("10001@im.example.com", "x@im.example.com")).map { it.empNo }).containsExactly("10001")

        dao.delete(listOf("10001@im.example.com"))
        assertThat(dao.get("10001@im.example.com")).isNull()
        assertThat(dao.count()).isEqualTo(3)
    }

    @Test
    fun employees_searchCandidates() = runTest {
        val dao = db.employeeDao()
        dao.upsert(
            listOf(
                emp("10001", "张三", "zhangsan", "zs", "d2", mobile = "13800000001"),
                emp("10002", "李四", "lisi", "ls", "d3"),
                emp("a_b", "王五", "wangwu", "ww", "d4"),
                emp("axb", "赵六", "zhaoliu", "zl", "d4"),
            ),
        )
        assertThat(dao.searchCandidates("三").map { it.empNo }).containsExactly("10001")
        assertThat(dao.searchCandidates("zh").map { it.empNo }).containsExactly("10001", "axb")
        assertThat(dao.searchCandidates("0000").map { it.empNo }).containsExactly("10001")
        assertThat(dao.getByDepartments(listOf("d3", "d4")).map { it.empNo }).containsExactly("10002", "a_b", "axb")
        // LIKE 通配符已转义：a\_ 只匹配工号 a_b，不匹配 axb
        assertThat(dao.searchCandidates("a\\_").map { it.empNo }).containsExactly("a_b")
    }

    @Test
    fun syncState() = runTest {
        val dao = db.syncStateDao()
        assertThat(dao.get("org:version")).isNull()
        dao.put(SyncStateEntity("org:version", "10"))
        dao.put(SyncStateEntity("org:version", "12"))
        assertThat(dao.get("org:version")).isEqualTo("12")
        dao.delete("org:version")
        assertThat(dao.get("org:version")).isNull()
    }
}
