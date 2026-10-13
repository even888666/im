package cn.android666.im.core.database

import androidx.room.migration.Migration

/**
 * 用户数据库的手写迁移（Android工程架构.md §5.3）。只新增表 / 列的版本用 Room 自动迁移（见 ImDatabase 的 autoMigrations），
 * 不需要写在这里。新增迁移时加到 [ALL]，并在 ImDatabaseMigrationTest 中测试。
 */
object ImDatabaseMigrations {

    /** 全部手写迁移，按版本顺序排列（1 → 2 为自动迁移，目前没有手写迁移） */
    val ALL: Array<Migration> = emptyArray()
}
