package cn.android666.im.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.database.entity.ContactEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.testing.RoomSchemaHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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

    /** 1 → 2（自动迁移，新增 contact 表）：原有组织架构数据保留，新表可用 */
    @Test
    fun migrate1To2_keepsDataAndAddsContactTable() = runTest {
        RoomSchemaHelper.createDatabase(context, ImDatabase::class.java.name, TEST_DB, 1).apply {
            execSQL("INSERT INTO department (id, parentId, name, sort, memberCount) VALUES ('d1', '', '总部', 10, 0)")
            execSQL("INSERT INTO sync_state (`key`, value) VALUES ('org:version', '7')")
            close()
        }
        val db = openWithRoom()
        try {
            assertThat(db.departmentDao().get("d1")?.name).isEqualTo("总部")
            assertThat(db.syncStateDao().get("org:version")).isEqualTo("7")
            db.contactDao().upsert(ContactEntity("10002@im.example.com", "to"))
            assertThat(db.contactDao().observeIsContact("10002@im.example.com").first()).isTrue()
        } finally {
            db.close()
        }
    }

    /** 2 → 3（自动迁移，新增 message、conversation、media_file）：原有数据保留，消息去重约束生效 */
    @Test
    fun migrate2To3_keepsDataAndAddsMessageTables() = runTest {
        RoomSchemaHelper.createDatabase(context, ImDatabase::class.java.name, TEST_DB, 2).apply {
            execSQL("INSERT INTO contact (jid, subscription) VALUES ('10002@im.example.com', 'both')")
            close()
        }
        val db = openWithRoom()
        try {
            assertThat(db.contactDao().observeIsContact("10002@im.example.com").first()).isTrue()
            val message = MessageEntity(
                msgId = "m1", conversationId = "10002@im.example.com", senderJid = "10002@im.example.com",
                direction = MessageEntity.DIRECTION_IN, type = "text", body = "hi", status = MessageEntity.STATUS_RECEIVED,
                serverTime = 1,
            )
            assertThat(db.messageDao().insert(message)).isGreaterThan(0L)
            assertThat(db.messageDao().insert(message)).isEqualTo(-1L)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
