package cn.android666.sip_demo

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import ch.qos.logback.classic.LoggerContext
import cn.android666.logback.android.LogbackManager
import cn.android666.logback.android.Logs
import org.doubango.ngn.utils.NgnKeys
import kotlin.system.measureTimeMillis
import org.slf4j.LoggerFactory

/**
 * AndroidX Startup 初始化器，用于替代 logback-android 库自带的 LogbackInitializer。
 *
 * 主要功能：
 * 1. 手动初始化 LogbackManager（根据 BuildConfig.DEBUG 设置 isDebug 属性），
 *    配合 logback.xml 中的条件判断，debug 版输出 TRACE 日志到 logcat，release 版仅写文件且不输出 logcat。
 * 2. 设置全局未捕获异常处理器，将崩溃信息通过 Logs.bug 写入 bug 日志文件后，
 *    再调用系统默认处理器保持系统崩溃行为。
 * 3. 崩溃时主动停止 logback（LoggerContext.stop()），确保日志落盘不丢失。
 */
class MyInitializer : Initializer<Unit> {

    /** 崩溃日志 Logger，用于捕获未处理异常并写入 bug 文件 */
    private val crashLog = Logs.bug("AppCrash")

    override fun create(context: Context) {
        // 初始化 logback，isDebug 参数决定 logback.xml 中 isDebug 条件的值
        LogbackManager.init(context, BuildConfig.DEBUG)

        // 紧接着把日志文件名前缀设成本机 SIP 账号。必须紧跟在 init 之后：
        // 这一刻还没有任何日志写出去，设完之后连本应用的第一条日志都能落进账号文件
        applySipAccountLogPrefix(context)

        // 保存系统默认的未捕获异常处理器，然后在自定义处理器中先记录 bug 日志再调用默认处理
        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // t 为发生异常的线程，由该线程调用此回调
            crashLog.error("未捕获的异常", e)

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

        Log.d(TAG, "Logback initialized by Startup from App")
    }

    /**
     * 把本机 SIP 账号设成日志文件名前缀，让日志从 `logs/log/app_log.log`
     * 变成 `logs/log/90040_log.log`（`sip/`、`bug/` 两个目录同理）。
     *
     * 现场话机是一批一批的，日志收上来必须能一眼看出是哪台机器的，这正是 `logback.xml` 里
     * 三个 SiftingAppender + `FileNamePrefixDiscriminator` 存在的意义。
     *
     * ## 为什么放在这里，而且不惜阻塞
     *
     * 这是**唯一能保证一条日志都不落到默认文件的时机**：本方法紧跟在 [LogbackManager.init]
     * 之后，此刻 logback 刚就绪、还没有任何日志写出去。若改成异步读，启动最初几十毫秒的日志
     * （状态栏注册、清理旧库等）会散落在 `app_log.log` 里，排障时得同时翻两个文件。
     *
     *
     * SIP 协议栈通过 SharedPreferences 同步保存账号，使用 NgnKeys 读取同一份持久化配置，
     * 不需要引入额外的 DataStore 或协程依赖。
     *
     * 账号为空时不设置：前缀直接拼进文件名，空串会得到 `_log.log` 这种怪名字，
     * 保持库的默认值 `app` 更好认。账号变化后的更新由
     * [cn.android666.sip_demo.App] 里的监听负责，不在这里处理。
     *
     * @param context Startup 传入的应用级 Context，用它读取 SIP SharedPreferences
     * @return Unit
     */
    private fun applySipAccountLogPrefix(context: Context) {
        var account: String? = null
        val cost = measureTimeMillis {
            account = try {
                context.getSharedPreferences(NgnKeys.SHARED_PREF_NAME, Context.MODE_PRIVATE)
                    .getString(NgnKeys.IDENTITY_IMPI, null)
                    ?.trim()
            } catch (e: Exception) {
                // 读配置失败不能连累应用启动，退回默认前缀即可
                Log.w(TAG, "读取 SIP 账号失败，日志文件名将使用默认前缀", e)
                null
            }
        }

        val prefix = account
        if (!prefix.isNullOrEmpty()) {
            LogbackManager.fileNamePrefix = prefix
        }
        // 这条日志放在设置之后打：此时前缀已生效，它自己也会落进账号文件里
        Logs.file(TAG).info(
            "日志文件名前缀=${prefix?.takeIf { it.isNotEmpty() } ?: "(默认)"}，读取耗时 ${cost}ms",
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // 无依赖其他 Startup 初始化器
        return emptyList()
    }

    private companion object {
        val TAG: String = MyInitializer::class.java.simpleName
    }
}
