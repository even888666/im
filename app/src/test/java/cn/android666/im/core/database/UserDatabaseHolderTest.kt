package cn.android666.im.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.database.DbFixtures.dept
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [UserDatabaseHolder] 测试：按工号分库，切换账号时数据互不干扰 */
@RunWith(RobolectricTestRunner::class)
class UserDatabaseHolderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** 生产环境的工厂（文件数据库），测试中允许主线程查询之外没有区别 */
    private val holder = UserDatabaseHolder { username ->
        Room.databaseBuilder(context, ImDatabase::class.java, ImDatabase.fileName(username))
            .addMigrations(*ImDatabaseMigrations.ALL)
            .build()
    }

    @After
    fun tearDown() {
        holder.close()
        context.deleteDatabase(ImDatabase.fileName("10001"))
        context.deleteDatabase(ImDatabase.fileName("10002"))
    }

    @Test
    fun switchUsers_dataIsolated() = runTest {
        holder.open("10001").departmentDao().upsert(listOf(dept("d1", "", "总部")))
        assertThat(holder.username).isEqualTo("10001")

        val b = holder.open("10002")
        assertThat(b.departmentDao().count()).isEqualTo(0)
        assertThat(holder.database.value).isSameInstanceAs(b)

        assertThat(holder.open("10001").departmentDao().count()).isEqualTo(1)
        assertThat(context.getDatabasePath("im_10001.db").exists()).isTrue()
    }

    @Test
    fun openSameUser_sameInstance() {
        val a = holder.open("10001")
        assertThat(holder.open("10001")).isSameInstanceAs(a)
    }

    @Test
    fun close_requireThrows() {
        holder.open("10001")
        holder.close()
        assertThat(holder.database.value).isNull()
        assertThat(holder.username).isNull()
        assertThrows(IllegalStateException::class.java) { holder.require() }
    }
}
