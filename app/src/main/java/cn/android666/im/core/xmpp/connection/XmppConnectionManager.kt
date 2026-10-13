package cn.android666.im.core.xmpp.connection

import cn.android666.im.core.log.Logs
import cn.android666.im.core.network.NetworkMonitor
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.LoginResult
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 连接管理事件，供 SessionManager 和界面使用。
 */
sealed interface ConnectionEvent {

    /**
     * 已认证（首次登录或重连成功）。
     *
     * @property resumed 是否流恢复。流恢复时只需补拉消息，否则要重新执行登录后的步骤（协议规范 §2.3）
     * @property isReconnect 是否为断线后的重连（false 表示本次会话的首次登录）
     */
    data class Connected(val resumed: Boolean, val isReconnect: Boolean) : ConnectionEvent

    /**
     * 不再自动重连，需要用户处理：被挤下线、账号被禁用、认证失败（协议规范 §2.4）。
     *
     * @property reason 原因
     */
    data class Stopped(val reason: DisconnectReason) : ConnectionEvent
}

/**
 * XMPP 连接管理（Android工程架构.md §4.3）：负责登录后的连接保持与断线重连，是长连接可靠性的核心。
 *
 * - 连接因网络原因断开 → 按 [ReconnectPolicy] 退避重连，优先流恢复（由 [XmppClient.reconnect] 实现）；
 * - 被挤下线、账号被禁用、认证失败 → 不重连，发出 [ConnectionEvent.Stopped]；
 * - 无网络时暂停重连，网络恢复时重置退避并立即重连；
 * - 已连接时默认网络发生切换（Wi-Fi ↔ 移动网络 ↔ 专网 APN）→ 立即检测连接是否失效（[XmppClient.checkAlive]），
 *   不必等心跳超时；
 * - [reconnectNow]：回到前台等时机主动触发立即重连。
 *
 * 应用级单例，由 AppContainer 持有，与界面生命周期无关；所有协程运行在 [scope] 中。
 *
 * @property client XMPP 客户端
 * @property networkMonitor 网络状态
 * @property scope 应用级协程作用域
 * @property policyFactory 退避策略工厂，测试时可去掉随机抖动
 */
class XmppConnectionManager(
    private val client: XmppClient,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val policyFactory: () -> ReconnectPolicy = { ReconnectPolicy() },
) {

    private val log = Logs.file(TAG)

    /** 连接状态，直接来自 [XmppClient] */
    val state: StateFlow<ConnectionState> = client.connectionState

    private val eventFlow = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** 连接事件 */
    val events: SharedFlow<ConnectionEvent> = eventFlow.asSharedFlow()

    /** 立即重连的信号（网络恢复、回到前台）；CONFLATED：多次触发合并为一次 */
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)

    /** 当前的监管协程（断线检测 + 重连），停止时取消 */
    private var supervisorJob: Job? = null

    /** 网络恢复监听协程 */
    private var networkJob: Job? = null

    /** 当前会话的凭证；重连时 XmppClient 无法恢复会话（例如从未登录成功）则用它重新登录 */
    @Volatile
    private var credential: Credential? = null

    /** 退避策略，只在监管协程中使用 */
    private var policy = policyFactory()

    /**
     * 登录一次（用于登录页：用户需要立即知道成功或失败）。成功后开始监管连接。
     *
     * @param credential 登录凭证
     * @return 登录结果
     * @throws XmppException 登录失败（不会自动重试）
     */
    suspend fun login(credential: Credential): LoginResult {
        stopSupervising()
        this.credential = credential
        val result = client.login(credential)
        log.info("登录成功，开始监管连接")
        eventFlow.emit(ConnectionEvent.Connected(resumed = false, isReconnect = false))
        startSupervising(needInitialLogin = false)
        return result
    }

    /**
     * 在后台登录并保持连接（用于冷启动自动登录、开机自启）：网络原因失败会按退避策略一直重试，
     * 直到成功或遇到不可重试的错误（发出 [ConnectionEvent.Stopped]）。
     *
     * @param credential 已保存的凭证
     */
    fun start(credential: Credential) {
        stopSupervising()
        this.credential = credential
        log.info("后台登录：工号={}", credential.username)
        startSupervising(needInitialLogin = true)
    }

    /**
     * 停止连接管理并退出登录（用户主动退出、切换账号）。
     */
    suspend fun stop() {
        log.info("停止连接管理并退出登录")
        stopSupervising()
        credential = null
        client.logout()
    }

    /**
     * 请求立即重连（网络恢复、回到前台时调用）：重置退避，正在等待的重连立即执行。已连接时无效果。
     */
    fun reconnectNow() {
        // 已连接时忽略：否则信号会留在通道里，下次断线时跳过退避直接重连
        if (state.value is ConnectionState.Authenticated) return
        policy.reset()
        reconnectSignal.trySend(Unit)
    }

    /**
     * 启动监管协程和网络监听。
     *
     * @param needInitialLogin 是否需要先登录（后台登录时为 true）
     */
    private fun startSupervising(needInitialLogin: Boolean) {
        policy = policyFactory()
        // 清掉之前残留的信号，避免新会话一开始就被误触发
        reconnectSignal.tryReceive()
        supervisorJob = scope.launch { supervise(needInitialLogin) }
        networkJob = scope.launch {
            // drop(1)：跳过当前值，只关心之后的变化
            networkMonitor.status.drop(1).collect { status ->
                if (!status.available) return@collect
                if (state.value is ConnectionState.Authenticated) {
                    // 已连接时切换了网络：旧网络上的连接可能已失效，主动检测；失效时客户端会断开，监管协程负责重连
                    log.info("已连接时网络切换为 {}，检测连接是否可用", status.networkId)
                    client.checkAlive()
                } else {
                    log.info("网络可用（{}），触发立即重连", status.networkId)
                    reconnectNow()
                }
            }
        }
    }

    /** 取消监管协程和网络监听 */
    private fun stopSupervising() {
        supervisorJob?.cancel()
        networkJob?.cancel()
        supervisorJob = null
        networkJob = null
    }

    /**
     * 监管循环：等待断线 → 重连，直到遇到不可重连的原因。
     *
     * @param needInitialLogin 是否需要先完成首次登录
     */
    private suspend fun supervise(needInitialLogin: Boolean) {
        if (needInitialLogin && !connectWithRetry(isReconnect = false)) return
        while (true) {
            // 等待连接断开
            val disconnected = state.first { it is ConnectionState.Disconnected } as ConnectionState.Disconnected
            val reason = disconnected.reason
            if (!reason.shouldReconnect) {
                log.warn("连接断开且不再重连：{}", reason)
                eventFlow.emit(ConnectionEvent.Stopped(reason))
                return
            }
            log.info("连接断开（{}），开始重连", reason)
            if (!connectWithRetry(isReconnect = true)) return
        }
    }

    /**
     * 按退避策略反复尝试，直到成功或遇到不可重试的错误。
     *
     * @param isReconnect 是否为断线重连（false 为首次登录）
     * @return 成功返回 true；遇到不可重试的错误返回 false（已发出 Stopped 事件）
     */
    private suspend fun connectWithRetry(isReconnect: Boolean): Boolean {
        while (true) {
            // 无网络时不尝试，等网络恢复（恢复时 reconnectNow 会重置退避）
            if (!networkMonitor.status.value.available) {
                log.info("无网络，等待网络恢复后再连接")
                networkMonitor.status.first { it.available }
            }
            val delayMillis = policy.nextDelayMillis()
            if (delayMillis > 0) {
                log.info("第 {} 次尝试前等待 {}ms", policy.attempts, delayMillis)
                // 等待期间收到立即重连信号则提前结束等待
                withTimeoutOrNull(delayMillis) { reconnectSignal.receive() }
            }
            val credential = credential ?: return false
            try {
                val result = if (isReconnect) reconnectOrRelogin(credential) else client.login(credential)
                log.info("{}成功：流恢复={}，共尝试 {} 次", if (isReconnect) "重连" else "登录", result.resumed, policy.attempts)
                policy.reset()
                eventFlow.emit(ConnectionEvent.Connected(result.resumed, isReconnect))
                return true
            } catch (e: XmppException) {
                val stopReason = e.stopReason()
                if (stopReason != null) {
                    log.warn("{}失败且不再重试：{}", if (isReconnect) "重连" else "登录", e.message)
                    eventFlow.emit(ConnectionEvent.Stopped(stopReason))
                    return false
                }
                log.warn("第 {} 次{}失败：{}", policy.attempts, if (isReconnect) "重连" else "登录", e.message)
            }
        }
    }

    /**
     * 重连；客户端没有可恢复的连接（例如进程重启后）时改为完整登录。
     *
     * @param credential 凭证
     * @return 登录结果
     */
    private suspend fun reconnectOrRelogin(credential: Credential): LoginResult = try {
        client.reconnect()
    } catch (e: XmppException.NotConnected) {
        log.info("没有可恢复的连接，改为完整登录")
        client.login(credential)
    }

    /**
     * 不可重试的错误对应的停止原因。
     *
     * @return 停止原因；可以重试时返回 null
     */
    private fun XmppException.stopReason(): DisconnectReason? = when (this) {
        is XmppException.AuthenticationFailed -> DisconnectReason.AuthenticationFailed
        is XmppException.Conflict -> DisconnectReason.Conflict
        is XmppException.AccountDisabled -> DisconnectReason.AccountDisabled
        else -> null
    }

    private companion object {
        const val TAG = "XmppConnectionManager"

        /** 事件缓冲：订阅者处理慢时不阻塞连接管理 */
        const val EVENT_BUFFER = 16
    }
}
