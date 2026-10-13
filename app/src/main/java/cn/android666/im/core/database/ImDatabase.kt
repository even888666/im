package cn.android666.im.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import cn.android666.im.core.database.dao.ContactDao
import cn.android666.im.core.database.dao.ConversationDao
import cn.android666.im.core.database.dao.DepartmentDao
import cn.android666.im.core.database.dao.EmployeeDao
import cn.android666.im.core.database.dao.MediaFileDao
import cn.android666.im.core.database.dao.MessageDao
import cn.android666.im.core.database.dao.SyncStateDao
import cn.android666.im.core.database.entity.ContactEntity
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.DepartmentEntity
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.core.database.entity.MediaFileEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.database.entity.SyncStateEntity

/**
 * 用户数据库（Android工程架构.md §5）：每个账号一个库 `im_{工号}.db`，由 [UserDatabaseHolder] 打开和关闭。
 *
 * 升级规则（§5.3）：每次修改表结构都要把 version 加 1，并提供迁移：只是新增表 / 列时用 Room 的自动迁移（[AutoMigration]），
 * 其他变化在 [ImDatabaseMigrations] 中手写 Migration；都要在 ImDatabaseMigrationTest 中写迁移测试。
 * 禁止 fallbackToDestructiveMigration（会丢失聊天记录）。schema 导出在 app/schemas/，纳入版本控制。
 *
 * 版本历史：
 * - 1：组织架构缓存（department、employee）、同步游标（sync_state）—— 开发计划 3.12
 * - 2：常用联系人（contact，自动迁移）—— 开发计划 3.19
 * - 3：消息、会话、附件（message、conversation、media_file，自动迁移）—— 开发计划 4.1
 */
@Database(
    entities = [
        DepartmentEntity::class, EmployeeEntity::class, SyncStateEntity::class, ContactEntity::class,
        MessageEntity::class, ConversationEntity::class, MediaFileEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class ImDatabase : RoomDatabase() {

    /**
     * 部门表。
     *
     * @return DAO
     */
    abstract fun departmentDao(): DepartmentDao

    /**
     * 员工表。
     *
     * @return DAO
     */
    abstract fun employeeDao(): EmployeeDao

    /**
     * 同步游标。
     *
     * @return DAO
     */
    abstract fun syncStateDao(): SyncStateDao

    /**
     * 常用联系人（花名册）。
     *
     * @return DAO
     */
    abstract fun contactDao(): ContactDao

    /**
     * 消息。
     *
     * @return DAO
     */
    abstract fun messageDao(): MessageDao

    /**
     * 会话。
     *
     * @return DAO
     */
    abstract fun conversationDao(): ConversationDao

    /**
     * 附件本地状态。
     *
     * @return DAO
     */
    abstract fun mediaFileDao(): MediaFileDao

    companion object {
        /**
         * 用户数据库的文件名。
         *
         * @param username 工号
         * @return 文件名，如 im_10001.db
         */
        fun fileName(username: String): String = "im_$username.db"
    }
}
