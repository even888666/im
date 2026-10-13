package cn.android666.im.core.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.qos.logback.classic.LoggerContext
import cn.android666.logback.android.LogbackManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.slf4j.LoggerFactory
import java.io.File

/**
 * [LogInitializer] 测试：在 Robolectric 上真实初始化 logback，检查日志文件的目录和文件名前缀。
 */
@RunWith(RobolectricTestRunner::class)
class LogInitializerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var logDir: File

    /** 测试前的全局未捕获异常处理器，测试后恢复，避免影响其他测试 */
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logDir = tempFolder.newFolder("logs")
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        // 每个测试结束时都会 stop() LoggerContext 来让日志落盘；被 stop 的 LoggerContext 再次初始化后
        // 不会写文件（logback 的行为，生产环境中 stop 只发生在崩溃、进程即将退出时，不受影响），
        // 所以同一 JVM 中运行下一个测试前要先重新 start
        (LoggerFactory.getILoggerFactory() as? LoggerContext)?.let { if (!it.isStarted) it.start() }
        // 文件名前缀是库中的全局单例，上一个测试设置的工号会残留，恢复为默认值
        LogbackManager.fileNamePrefix = LogFilePrefix.DEFAULT
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        stopLogback()
    }

    /** 列出日志目录下的所有文件，断言失败时帮助定位日志写到了哪里 */
    private fun listLogFiles(): String = logDir.walk().filter { it.isFile }.joinToString { it.relativeTo(logDir).path }

    /**
     * 等待异步 Appender 把日志写进文件。
     *
     * 业务日志和 xmpp 日志经过 AsyncAppender，由后台线程写文件；如果直接 stop()，后台线程可能正好在
     * 首次创建文件时被中断，导致这条日志丢失。所以先轮询到文件中出现预期内容，再 stop()。
     * （崩溃日志走同步 Appender，不受此影响。）
     *
     * @param file 期望写入的日志文件
     * @param expected 期望出现的内容
     */
    private fun awaitLogged(file: File, expected: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.readText().contains(expected)) return
            Thread.sleep(20)
        }
        throw AssertionError("5 秒内日志未写入 ${file.name}：$expected；现有文件：${listLogFiles()}")
    }

    /** 停止 logback：异步 Appender 会把队列中的日志刷到文件 */
    private fun stopLogback() {
        (LoggerFactory.getILoggerFactory() as? LoggerContext)?.stop()
    }

    @Test
    fun setUp_withSavedEmpNo_writesFilesWithEmpNoPrefix() {
        LogFilePrefixStore.create(context).save("10086")

        LogInitializer.setUp(context, isDebug = true, logDir = logDir)
        Logs.file("Test").info("业务日志")
        Logs.xmpp("Test").info("<message/>")

        val appLog = File(logDir, "log/10086_log.log")
        val xmppLog = File(logDir, "xmpp/10086_xmpp.log")
        awaitLogged(appLog, "业务日志")
        awaitLogged(xmppLog, "<message/>")
        stopLogback()

        assertWithMessage(listLogFiles()).that(appLog.exists()).isTrue()
        assertThat(appLog.readText()).contains("业务日志")
        // 初始化器自己的第一条日志也进了工号文件
        assertThat(appLog.readText()).contains("日志文件名前缀=10086")
        assertThat(xmppLog.readText()).contains("<message/>")
        // xmpp 日志不应混进业务日志
        assertThat(appLog.readText()).doesNotContain("<message/>")
    }

    @Test
    fun setUp_withoutEmpNo_usesDefaultPrefix() {
        LogInitializer.setUp(context, isDebug = true, logDir = logDir)
        Logs.file("Test").info("未登录")

        awaitLogged(File(logDir, "log/${LogFilePrefix.DEFAULT}_log.log"), "未登录")
    }

    /** release 配置下 DEBUG 级别的业务日志不写文件 */
    @Test
    fun setUp_release_skipsDebugLevel() {
        LogInitializer.setUp(context, isDebug = false, logDir = logDir)
        Logs.file("Test").debug("调试细节")
        Logs.file("Test").info("正常流程")
        val file = File(logDir, "log/${LogFilePrefix.DEFAULT}_log.log")
        // 日志按顺序处理：等到后写的"正常流程"出现，先写的 debug 日志如果要写早就写了
        awaitLogged(file, "正常流程")

        val text = file.readText()
        assertThat(text).contains("正常流程")
        assertThat(text).doesNotContain("调试细节")
    }

    /** 未捕获异常：先写 bug 日志并落盘，再转交原来的默认处理器 */
    @Test
    fun crashHandler_writesBugLogThenDelegates() {
        val delegated = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated += e }
        LogInitializer.setUp(context, isDebug = true, logDir = logDir)

        val crash = IllegalStateException("模拟崩溃")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

        assertThat(delegated).containsExactly(crash)
        val bugLog = File(logDir, "bug/${LogFilePrefix.DEFAULT}_bug.log").readText()
        assertThat(bugLog).contains("模拟崩溃")
    }
}
