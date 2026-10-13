package cn.android666.im.core.database

import android.content.Context
import androidx.room.Room
import cn.android666.im.core.log.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前账号的用户数据库（Android工程架构.md §5.1）：登录时按工号打开 `im_{工号}.db`，切换账号时关闭旧库、打开新库，
 * 退出登录时关闭。定制机可能多人共用，各账号的数据互不干扰。
 *
 * 线程安全：[open] / [close] 加锁执行；读取 [current] 无锁。
 *
 * @property factory 按工号创建数据库（生产环境见 [create]，测试中可用内存数据库）
 */
class UserDatabaseHolder(private val factory: (username: String) -> ImDatabase) {

    private val log = Logs.file(TAG)

    /**
     * 已打开的数据库。
     *
     * @property username 工号
     * @property database 数据库
     */
    private data class Opened(val username: String, val database: ImDatabase)

    /** 当前打开的数据库及其工号；只在加锁的 [open] / [close] 中修改 */
    @Volatile
    private var opened: Opened? = null

    private val databaseFlow = MutableStateFlow<ImDatabase?>(null)

    /** 当前打开的数据库；未登录时为 null */
    val database: StateFlow<ImDatabase?> = databaseFlow.asStateFlow()

    /** 当前数据库所属的工号；未打开时为 null */
    val username: String? get() = opened?.username

    /**
     * 打开指定账号的数据库；已经打开的是同一账号时直接返回，是其他账号时先关闭。
     *
     * @param username 工号
     * @return 数据库
     */
    @Synchronized
    fun open(username: String): ImDatabase {
        opened?.let { current ->
            if (current.username == username) return current.database
            log.info("切换账号，关闭用户数据库：{}", current.username)
            current.database.close()
        }
        log.info("打开用户数据库：{}", ImDatabase.fileName(username))
        val database = factory(username)
        opened = Opened(username, database)
        databaseFlow.value = database
        return database
    }

    /** 关闭当前数据库（退出登录） */
    @Synchronized
    fun close() {
        val current = opened ?: return
        log.info("关闭用户数据库：{}", current.username)
        current.database.close()
        opened = null
        databaseFlow.value = null
    }

    /**
     * 当前打开的数据库。
     *
     * @return 数据库
     * @throws IllegalStateException 未登录（数据库未打开）
     */
    fun require(): ImDatabase = databaseFlow.value ?: throw IllegalStateException("用户数据库未打开（未登录）")

    companion object {
        private const val TAG = "UserDatabase"

        /**
         * 生产环境的数据库工厂：文件数据库，注册全部迁移，不允许破坏性迁移。
         *
         * @param context Context
         * @return 按工号创建数据库的函数
         */
        fun create(context: Context): (String) -> ImDatabase = { username ->
            Room.databaseBuilder(context.applicationContext, ImDatabase::class.java, ImDatabase.fileName(username))
                .addMigrations(*ImDatabaseMigrations.ALL)
                .build()
        }
    }
}
