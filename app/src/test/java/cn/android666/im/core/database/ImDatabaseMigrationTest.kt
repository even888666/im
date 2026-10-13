package cn.android666.im.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.testing.RoomSchemaHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 用户数据库的 schema 与迁移测试（Android工程架构.md §5.3）：按 app/schemas/ 中的历史 schema 建库，
 * 用 Room（注册全部迁移）打开，Room 会执行迁移并校验表结构。
 * 新增版本时在这里补充"从上一版本迁移"的测试（插入旧数据 → 迁移 → 检查数据仍在）。
 */
@RunWith(RobolectricTestRunner::class)
class ImDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    /**
     * 用 Room 打开测试数据库。
     *
     * @return 数据库
     */
    private fun openWithRoom(): ImDatabase =
        Room.databaseBuilder(context, ImDatabase::class.java, TEST_DB).addMigrations(*ImDatabaseMigrations.ALL).build()

    /** 第 1 版：schema 文件与当前实体定义一致（identity hash 匹配，Room 打开成功），数据可读 */
    @Test
    fun version1_matchesEntities() = runTest {
        RoomSchemaHelper.createDatabase(context, ImDatabase::class.java.name, TEST_DB, 1).apply {
            execSQL("INSERT INTO sync_state (`key`, value) VALUES ('org:version', '5')")
            close()
        }
        val db = openWithRoom()
        try {
            assertThat(db.syncStateDao().get("org:version")).isEqualTo("5")
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
