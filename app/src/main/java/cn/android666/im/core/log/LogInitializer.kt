package cn.android666.im.core.log

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import ch.qos.logback.classic.LoggerContext
import cn.android666.im.BuildConfig
import cn.android666.logback.android.LogbackManager
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * AndroidX Startup 初始化器，用于替代 logback-android 库自带的 LogbackInitializer。
 *
 * 改编自 `日志库/MyInitializer.kt`（原 SIP 项目），调整：包名改为 `cn.android666.im`、去掉 SIP 相关代码、
 * 日志文件名前缀改为当前登录的工号（见 Android工程架构.md §8）。
 *
 * 主要功能：
 * 1. 手动初始化 LogbackManager（根据 BuildConfig.DEBUG 设置 isDebug 属性），
 *    配合 logback.xml 中的条件判断，debug 版输出 TRACE 日志到 logcat，release 版仅写文件且不输出 logcat。
 * 2. 设置全局未捕获异常处理器，将崩溃信息通过 Logs.bug 写入 bug 日志文件后，
 *    再调用系统默认处理器保持系统崩溃行为。
 * 3. 崩溃时主动停止 logback（LoggerContext.stop()），确保日志落盘不丢失。
 */
class LogInitializer : Initializer<Unit> {

    /**
     * Startup 回调：完成日志初始化。
     *
     * @param context Startup 传入的应用级 Context
     */
    override fun create(context: Context) {
        setUp(context, BuildConfig.DEBUG)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // 无依赖其他 Startup 初始化器
        return emptyList()
    }

    companion object {
        private const val TAG = "LogInitializer"

        /**
         * 初始化日志。从 [create] 中拆出来，便于测试时指定日志目录和 debug 开关。
         *
         * @param context 应用级 Context
         * @param isDebug 是否 debug 版，决定 logback.xml 中 isDebug 条件的值
         * @param logDir 日志根目录；null 时使用库的默认目录（外部私有目录下的 `logs`）
         */
        internal fun setUp(context: Context, isDebug: Boolean, logDir: File? = null) {
            // 初始化 logback，isDebug 参数决定 logback.xml 中 isDebug 条件的值
            if (logDir == null) {
                LogbackManager.init(context, isDebug)
            } else {
                LogbackManager.init(context, isDebug, logDir)
            }

            // 紧接着把日志文件名前缀设成上次登录的工号。必须紧跟在 init 之后：
            // 这一刻还没有任何日志写出去，设完之后连本应用的第一条日志都能落进工号文件
            applyEmpNoLogPrefix(context)

            installCrashHandler()

            Log.d(TAG, "Logback initialized by Startup from App")
        }

        /**
         * 把上次登录的工号设成日志文件名前缀，让日志从 `logs/log/app_log.log`
         * 变成 `logs/log/10086_log.log`（`xmpp/`、`bug/` 两个目录同理）。
         *
         * 定制机可能多人共用，日志收上来必须能一眼看出是谁的，这正是 `logback.xml` 里
         * 三个 SiftingAppender + `FileNamePrefixDiscriminator` 存在的意义。
         *
         * ## 为什么放在这里，而且不惜阻塞
         *
         * 这是**唯一能保证一条日志都不落到默认文件的时机**：本方法紧跟在 [LogbackManager.init]
         * 之后，此刻 logback 刚就绪、还没有任何日志写出去。若改成异步读，启动最初几十毫秒的日志
         * 会散落在 `app_log.log` 里，排障时得同时翻两个文件。
         *
         * 工号由 [LogFilePrefixManager] 在登录成功时同步写入专用的 SharedPreferences（见 [LogFilePrefixStore]），
         * 这里同步读取，不需要引入额外的 DataStore 或协程依赖。
         *
         * 工号为空时不设置：前缀直接拼进文件名，空串会得到 `_log.log` 这种怪名字，
         * 保持库的默认值 `app` 更好认。登录 / 切换账号后的更新由 [LogFilePrefixManager] 负责，不在这里处理。
         *
         * @param context 应用级 Context，用它读取 SharedPreferences
         */
        private fun applyEmpNoLogPrefix(context: Context) {
            var empNo: String? = null
            val cost = measureTimeMillis {
                empNo = try {
                    LogFilePrefixStore.create(context).read()
                } catch (e: Exception) {
                    // 读配置失败不能连累应用启动，退回默认前缀即可
                    Log.w(TAG, "读取工号失败，日志文件名将使用默认前缀", e)
                    null
                }
            }

            val prefix = empNo
            if (!prefix.isNullOrEmpty()) {
                LogbackManager.fileNamePrefix = prefix
            }
            // 这条日志放在设置之后打：此时前缀已生效，它自己也会落进工号文件里
            Logs.file(TAG).info(
                "日志文件名前缀={}，读取耗时 {}ms",
                prefix?.takeIf { it.isNotEmpty() } ?: "(默认)",
                cost,
            )
        }

        /**
         * 设置全局未捕获异常处理器：先写 bug 日志并让 logback 落盘，再交给系统默认处理器。
         */
        private fun installCrashHandler() {
            /** 崩溃日志 Logger，用于捕获未处理异常并写入 bug 文件 */
            val crashLog = Logs.bug("AppCrash")

            // 保存系统默认的未捕获异常处理器，然后在自定义处理器中先记录 bug 日志再调用默认处理
            val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                // t 为发生异常的线程，由该线程调用此回调
                crashLog.error("未捕获的异常，线程={}", t.name, e)

                // 刷新并关闭 logback，确保日志落盘
                (LoggerFactory.getILoggerFactory() as? LoggerContext)?.stop()

                defaultUncaughtExceptionHandler?.uncaughtException(t, e)
                /*
                 * defaultUncaughtExceptionHandler 的功能：
                 *  1. kill 掉进程
                 *  2. 在部分 Android 版本弹"xxx已停止运行"对话框（Android 7 会弹，Android 11 不弹直接重启）
                 *  3. 控制台输出异常堆栈
                 * 保留系统默认行为，所以将异常转发给 defaultUncaughtExceptionHandler 处理。
                 */
            }
        }
    }
}
