package cn.android666.im.service

import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.ConnectionState

/**
 * 保活检查（功能清单 §3"进程被杀后自恢复"）：开机广播、应用升级、WorkManager 定期巡检时调用。
 * 纯逻辑，所有外部操作通过构造函数注入，便于单元测试。
 *
 * @property isServiceRunning 前台服务是否在运行
 * @property hasSavedCredential 是否有可自动登录的凭证（没有则不需要保活）
 * @property startService 启动前台服务，参数为原因；系统不允许时抛出 IllegalStateException
 * @property connectionState 当前连接状态
 * @property reconnectNow 触发立即重连
 */
class KeepAliveChecker(
    private val isServiceRunning: () -> Boolean,
    private val hasSavedCredential: suspend () -> Boolean,
    private val startService: (String) -> Unit,
    private val connectionState: () -> ConnectionState,
    private val reconnectNow: () -> Unit,
) {

    /** 检查结果 */
    enum class Result {
        /** 没有保存的凭证，不需要保活 */
        NO_CREDENTIAL,

        /** 服务在运行，连接正常或正在连接 */
        HEALTHY,

        /** 服务在运行但连接已断开，已触发立即重连 */
        RECONNECT_TRIGGERED,

        /** 服务没有运行，已重新启动 */
        SERVICE_STARTED,

        /** 服务没有运行，且系统不允许在后台启动（需引导用户加入电池优化白名单） */
        START_NOT_ALLOWED,
    }

    private val log = Logs.file(TAG)

    /**
     * 执行一次检查。
     *
     * @param trigger 触发来源（开机、升级、定期巡检），写日志用
     * @return 检查结果
     */
    suspend fun check(trigger: String): Result {
        val result = doCheck(trigger)
        log.info("保活检查（{}）：{}", trigger, result)
        return result
    }

    /**
     * 检查逻辑。
     *
     * @param trigger 触发来源
     * @return 检查结果
     */
    private suspend fun doCheck(trigger: String): Result {
        if (!hasSavedCredential()) return Result.NO_CREDENTIAL
        if (!isServiceRunning()) {
            return try {
                startService(trigger)
                Result.SERVICE_STARTED
            } catch (e: IllegalStateException) {
                // Android 12+ 后台启动前台服务受限（ForegroundServiceStartNotAllowedException 是其子类）
                log.warn("系统不允许在后台启动前台服务，需要用户把应用加入电池优化白名单", e)
                Result.START_NOT_ALLOWED
            }
        }
        val state = connectionState()
        if (state is ConnectionState.Disconnected && state.reason.shouldReconnect) {
            reconnectNow()
            return Result.RECONNECT_TRIGGERED
        }
        return Result.HEALTHY
    }

    private companion object {
        const val TAG = "KeepAlive"
    }
}
