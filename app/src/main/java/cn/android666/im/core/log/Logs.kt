package cn.android666.im.core.log

import org.slf4j.Logger
import cn.android666.logback.android.Logs as LibLogs

/**
 * 本项目的日志门面，封装 logback-android 库的 `Logs`，按 Android工程架构.md §8 划分三类日志：
 *
 * | 分类 | 目录 | 内容 |
 * | --- | --- | --- |
 * | `file` | `logs/log` | 业务日志：连接、登录、消息收发、同步、文件传输、通话、服务启停、异常分支等 |
 * | `xmpp` | `logs/xmpp` | XMPP 报文与连接细节（报文必须脱敏，见 [LogSanitizer]） |
 * | `bug` | `logs/bug` | 崩溃 |
 *
 * 业务代码统一通过本对象获取 Logger，不直接使用库的 `Logs`，以后调整分类时只改这一处。
 */
object Logs {

    /** logback.xml 中 xmpp 日志的 logger 名称前缀 */
    private const val XMPP = "xmpp"

    /**
     * 获取业务日志 Logger（写入 `log` 目录，debug 版同时输出 logcat）。
     *
     * @param tag 日志标签，一般为类名
     * @return Logger
     */
    fun file(tag: String): Logger = LibLogs.file(tag)

    /**
     * 获取 XMPP 日志 Logger（写入 `xmpp` 目录，debug 版同时输出 logcat）。
     *
     * @param tag 日志标签，一般为类名
     * @return Logger
     */
    fun xmpp(tag: String): Logger = LibLogs.of(XMPP, tag)

    /**
     * 获取崩溃日志 Logger（只写入 `bug` 目录）。
     *
     * @param tag 日志标签
     * @return Logger
     */
    fun bug(tag: String): Logger = LibLogs.bug(tag)
}
