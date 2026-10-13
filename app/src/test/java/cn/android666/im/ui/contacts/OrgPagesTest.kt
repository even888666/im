package cn.android666.im.ui.contacts

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.App
import cn.android666.im.R
import cn.android666.im.core.database.entity.DepartmentEntity
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.ui.profile.EmployeeProfileActivity
import cn.android666.im.ui.widget.AvatarView
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * 组织架构浏览页、同事资料卡的 Robolectric 测试：使用真实的 App 容器，在 10001 的用户数据库中预置组织架构。
 */
@RunWith(RobolectricTestRunner::class)
class OrgPagesTest {

    private val app = ApplicationProvider.getApplicationContext<App>()

    @Before
    fun setUp() = runBlocking {
        val db = app.container.userDatabaseHolder.open("10001")
        db.departmentDao().upsert(
            listOf(DepartmentEntity("d1", "", "总部", 10, 0), DepartmentEntity("d2", "d1", "研发中心", 10, 1)),
        )
        db.employeeDao().upsert(
            listOf(
                EmployeeEntity(
                    "10002@im.example.com", "10002", "李四", "lisi", "ls", "d2", "Android工程师",
                    "13800000002", "", "lisi@example.com", 0, "",
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        app.container.userDatabaseHolder.close()
        app.deleteDatabase("im_10001.db")
    }

    /**
     * 推进主线程直到条件满足（Room 查询在后台线程执行）。
     *
     * @param condition 条件
     */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时")
    }

    /**
     * 列表中第 position 项的文字（部门名称或员工姓名）。
     *
     * @param list 列表
     * @param position 位置
     * @return 文字；该项还没有显示时为 null
     */
    private fun itemName(list: RecyclerView, position: Int): String? =
        list.findViewHolderForAdapterPosition(position)?.itemView?.findViewById<TextView>(R.id.name)?.text?.toString()

    @Test
    fun orgBrowse_drillDownAndBack() {
        ActivityScenario.launch(OrgBrowseActivity::class.java).use { scenario ->
            lateinit var list: RecyclerView
            scenario.onActivity { list = it.findViewById(R.id.list) }
            waitUntil { itemName(list, 0) == "总部" }

            // 进入 总部 → 研发中心 → 看到成员
            list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            waitUntil { itemName(list, 0) == "研发中心" }
            list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            waitUntil { itemName(list, 0) == "李四" }

            // 返回键回到上一级
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitUntil { itemName(list, 0) == "研发中心" }
        }
    }

    @Test
    fun profile_showsFields() {
        val intent = Intent(app, EmployeeProfileActivity::class.java).putExtra("jid", "10002@im.example.com")
        ActivityScenario.launch<EmployeeProfileActivity>(intent).use { scenario ->
            lateinit var name: TextView
            scenario.onActivity { name = it.findViewById(R.id.name) }
            waitUntil { name.text.toString() == "李四" }
            scenario.onActivity { activity ->
                assertThat(activity.findViewById<TextView>(R.id.headline).text.toString()).isEqualTo("Android工程师")
                assertThat(activity.findViewById<AvatarView>(R.id.avatar).displayedText.toString()).isEqualTo("李四")
                // 工号、部门、手机、邮箱（座机为空不显示）
                assertThat(activity.findViewById<android.view.ViewGroup>(R.id.fields).childCount).isEqualTo(4)
                assertThat(activity.findViewById<View>(R.id.notFound).visibility).isEqualTo(View.GONE)
            }
        }
    }
}
