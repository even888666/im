package cn.android666.im.core.database

import androidx.room.migration.Migration

/**
 * 用户数据库的全部迁移（Android工程架构.md §5.3）。新增迁移时加到 [ALL]，并在 ImDatabaseMigrationTest 中测试。
 */
object ImDatabaseMigrations {

    /** 全部迁移，按版本顺序排列（目前只有第 1 版，没有迁移） */
    val ALL: Array<Migration> = emptyArray()
}
