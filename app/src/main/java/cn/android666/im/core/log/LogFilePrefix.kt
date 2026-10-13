package cn.android666.im.core.log

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import cn.android666.logback.android.LogbackManager

/**
 * 日志文件名前缀相关的纯逻辑。
 *
 * 日志文件名形如 `logs/log/{前缀}_log.log`，前缀为当前登录的工号，现场收集日志时一眼就能看出是谁的手机
 * （见 Android工程架构.md §8）。
 */
object LogFilePrefix {

    /** 日志库默认前缀：未登录过时使用，与 logback-android 库的默认值一致 */
    const val DEFAULT = "app"

    /** 文件名中不安全的字符：只保留字母、数字、`.`、`_`、`-`（与协议规范 §1.1 工号字符集一致） */
    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

    /**
     * 清洗工号，得到可安全拼进文件名的前缀。
     *
     * 工号按协议只包含小写字母、数字、`.`、`_`、`-`，但持久化的数据可能被篡改或来自旧版本，
     * 这里仍做一次清洗，避免 `/` 等字符导致日志写到其他目录。
     *
     * @param raw 原始工号，可为 null
     * @return 清洗后的前缀；原始值为 null、空白或清洗后为空时返回 null（调用方应使用 [DEFAULT]）
     */
    fun sanitize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val safe = trimmed.replace(UNSAFE_CHARS, "_")
        // 全部是不安全字符时（如 "///"）清洗结果只剩下划线，没有辨识度，视为无效
        return safe.takeUnless { it.all { c -> c == '_' } }
    }
}

/**
 * 日志文件名前缀（工号）的持久化存储。
 *
 * 为什么用 SharedPreferences 而不是 DataStore：日志库在进程极早期（AndroidX Startup）初始化，
 * 必须**同步**读到工号，才能保证第一条日志就写进工号文件；DataStore 只能异步读取。
 * 因此这是项目中唯一允许使用 SharedPreferences 的地方（见 Android工程架构.md §1.3、§8）。
 *
 * @property prefs 专用的 SharedPreferences，只存工号这一项
 */
class LogFilePrefixStore(private val prefs: SharedPreferences) {

    /**
     * 读取上次登录的工号（已清洗）。
     *
     * @return 工号前缀；从未登录过或数据无效时返回 null
     */
    fun read(): String? = LogFilePrefix.sanitize(prefs.getString(KEY_EMP_NO, null))

    /**
     * 保存工号。使用 commit 同步写盘：登录后紧接着进程可能被杀，异步写入有丢失风险，
     * 而这里只写一个短字符串，同步写的耗时可以忽略。
     *
     * @param empNo 工号；传 null 时清除
     */
    fun save(empNo: String?) {
        prefs.edit(commit = true) {
            if (empNo == null) remove(KEY_EMP_NO) else putString(KEY_EMP_NO, empNo)
        }
    }

    companion object {
        /** SharedPreferences 文件名 */
        const val PREFS_NAME = "log_file_prefix"

        /** 工号的键 */
        private const val KEY_EMP_NO = "emp_no"

        /**
         * 创建基于应用私有 SharedPreferences 的实例。
         *
         * @param context 任意 Context，内部取 applicationContext
         * @return 存储实例
         */
        fun create(context: Context): LogFilePrefixStore =
            LogFilePrefixStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

/**
 * 登录 / 切换账号时更新日志文件名前缀：立即生效并持久化，下次冷启动时 [LogInitializer] 读取。
 *
 * @property store 前缀持久化存储
 * @property applyPrefix 让前缀生效的动作，默认设置 [LogbackManager.fileNamePrefix]；测试时可替换
 */
class LogFilePrefixManager(
    private val store: LogFilePrefixStore,
    private val applyPrefix: (String) -> Unit = { LogbackManager.fileNamePrefix = it },
) {

    /** 文件日志 */
    private val log = Logs.file(TAG)

    /**
     * 登录成功后调用：之后的日志写入该工号的文件。
     *
     * 退出登录时不需要调用任何方法：退出后到下一个账号登录前的日志仍写在上一个工号的文件里，
     * 便于排查"退出登录"本身的问题。
     *
     * @param empNo 登录的工号
     */
    fun onLogin(empNo: String) {
        val prefix = LogFilePrefix.sanitize(empNo) ?: LogFilePrefix.DEFAULT
        // 先在旧文件里记一笔，再切换，方便在旧文件中看到"切到了哪个账号"
        log.info("日志文件名前缀切换为 {}", prefix)
        applyPrefix(prefix)
        store.save(empNo)
        log.info("日志文件名前缀已切换为 {}", prefix)
    }

    private companion object {
        const val TAG = "LogFilePrefixManager"
    }
}
