package cn.android666.im.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.DepartmentEntity
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.data.org.OrgRepository

/**
 * 界面测试用的组织架构：Room 内存库中预置 总部 / 研发中心、总部 / 市场部 和 3 名员工，
 * 配合 [FakeXmppClient] 创建真实的 [OrgRepository]（需在 Robolectric 中使用）。
 */
class OrgTestData {

    /** 假的 XMPP 客户端（下拉刷新时的同步请求） */
    val client = FakeXmppClient()

    /** 当前账号的数据库（已打开 10001 的内存库） */
    val holder = UserDatabaseHolder {
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java).build()
    }

    /** 组织架构仓库 */
    val repository = OrgRepository(client, holder, FakeClock())

    init {
        holder.open("10001")
    }

    /** 写入预置数据 */
    suspend fun seed() {
        val db = holder.require()
        db.departmentDao().upsert(
            listOf(
                DepartmentEntity("d1", "", "总部", 10, 0),
                DepartmentEntity("d2", "d1", "研发中心", 10, 2),
                DepartmentEntity("d3", "d1", "市场部", 20, 1),
            ),
        )
        db.employeeDao().upsert(
            listOf(
                employee("10001", "张三", "zhangsan", "zs", "d2", "13800000001"),
                employee("10002", "李四", "lisi", "ls", "d2", ""),
                employee("10003", "王五", "wangwu", "ww", "d3", ""),
            ),
        )
    }

    /** 关闭数据库 */
    fun close() = holder.close()

    private fun employee(no: String, name: String, pinyin: String, initials: String, dept: String, mobile: String) =
        EmployeeEntity("$no@im.example.com", no, name, pinyin, initials, dept, "工程师", mobile, "", "$no@example.com", 0, "")
}
