package cn.android666.im.data.session

import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.connection.ConnectionEvent
import cn.android666.im.core.xmpp.connection.XmppConnectionManager
import cn.android666.im.core.xmpp.extension.account.ChangePasswordRequest
import cn.android666.im.data.account.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * 登录编排（Android工程架构.md §4.4）：登录流程的唯一入口。
 *
 * - [login]：登录页登录；[startAutoLogin]：冷启动自动登录；[logout]：退出登录；
 * - 每次连接认证成功（[ConnectionEvent.Connected]）后，按注册顺序执行 [steps]（协议规范 §2.2 / 附录 B）：
 *   首次登录或流恢复失败的重连执行全部步骤；流恢复只执行 [SessionStep.runOnResume] 为 true 的步骤；
 * - 账号需要强制改密时暂停，[changePassword] 成功后继续执行剩余步骤；
 * - 连接被结束（被挤下线、账号禁用、认证失败）时进入 [SessionState.Ended]，并按协议规范 §2.4 处理保存的密码。
 *
 * 应用级单例，在 [scope] 中运行。
 *
 * @property client XMPP 客户端（改密时发送 IQ）
 * @property connectionManager 连接管理
 * @property credentialStore 凭证存储
 * @property onUserLoggedIn 开始新会话时调用，参数为工号；生产环境中把日志文件名前缀切换为工号、打开该账号的用户数据库
 * @property steps 登录后的步骤，按执行顺序排列
 * @property scope 应用级协程作用域
 * @property onUserLoggedOut 退出登录时调用；生产环境中关闭用户数据库
 */
class SessionManager(
    private val client: XmppClient,
    private val connectionManager: XmppConnectionManager,
    private val credentialStore: CredentialStore,
    private val onUserLoggedIn: (String) -> Unit,
    private val steps: List<SessionStep>,
    private val scope: CoroutineScope,
    private val onUserLoggedOut: () -> Unit = {},
) {

    private val log = Logs.file(TAG)

    private val mutableState = MutableStateFlow<SessionState>(SessionState.LoggedOut)

    /** 会话状态 */
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** 当前会话的上下文；未登录时为 null */
    @Volatile
    private var context: SessionContext? = null

    /** 当前登录的工号；未登录或会话已结束时为 null */
    val currentUsername: String? get() = context?.username

    /** 正在执行的步骤协程；新的连接事件到来时取消旧的 */
    private var stepsJob: Job? = null

    /** 强制改密暂停时，下一个要执行的步骤下标 */
    @Volatile
    private var pausedAt: Int? = null

    init {
        // 先订阅事件再允许登录，避免错过事件（events 不重放）。
        // 用 UNDISPATCHED 在构造函数返回前就完成订阅：普通 launch 要等调度器调度才开始收集，
        // 如果构造后马上登录，Connected 事件可能在订阅前发出而丢失
        scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionManager.events.collect(::onConnectionEvent) }
    }

    /**
     * 登录页登录：成功后保存凭证、切换日志文件名前缀，随后在后台执行登录后的步骤。
     *
     * @param credential 用户输入的工号和密码
     * @throws XmppException 登录失败（账号或密码错误、网络错误等）
     */
    suspend fun login(credential: Credential) {
        log.info("用户登录：工号={}", credential.username)
        prepareSession(credential.username)
        try {
            connectionManager.login(credential)
        } catch (e: XmppException) {
            mutableState.value = SessionState.LoggedOut
            context = null
            throw e
        }
        credentialStore.save(credential)
    }

    /**
     * 冷启动自动登录：有保存的凭证时在后台登录（网络原因失败会一直重试）。
     *
     * @return 是否开始了自动登录；没有可用凭证时返回 false，应显示登录页
     */
    suspend fun startAutoLogin(): Boolean {
        val saved = credentialStore.load()
        val credential = saved?.toCredential()
        if (credential == null) {
            log.info("没有可自动登录的凭证（{}），显示登录页", saved)
            return false
        }
        log.info("自动登录：工号={}", credential.username)
        prepareSession(credential.username)
        connectionManager.start(credential)
        return true
    }

    /**
     * 强制改密（协议规范 §3.2）：成功后更新保存的密码，并继续执行暂停的步骤。
     *
     * @param newPassword 新密码
     * @throws XmppException 改密失败（例如 weak-password，见 [XmppException.StanzaError.appErrorCode]）
     */
    suspend fun changePassword(newPassword: String) {
        val current = context ?: throw XmppException.NotConnected()
        client.sendIq(ChangePasswordRequest(current.username, newPassword))
        credentialStore.updatePassword(newPassword)
        log.info("修改密码成功")
        val resumeFrom = pausedAt
        if (resumeFrom != null) {
            pausedAt = null
            mutableState.value = SessionState.Starting(current.username)
            stepsJob = scope.launch { runSteps(current, startIndex = resumeFrom, resumed = false) }
        }
    }

    /**
     * 退出登录：断开连接、清除保存的密码（保留工号供登录页回显）。
     */
    suspend fun logout() {
        log.info("退出登录")
        stepsJob?.cancel()
        connectionManager.stop()
        credentialStore.clearPassword()
        context = null
        pausedAt = null
        mutableState.value = SessionState.LoggedOut
        onUserLoggedOut()
    }

    /**
     * 开始新会话：创建上下文、切换日志文件名前缀、状态改为 Starting。
     *
     * @param username 工号
     */
    private fun prepareSession(username: String) {
        stepsJob?.cancel()
        pausedAt = null
        context = SessionContext(username)
        onUserLoggedIn(username)
        mutableState.value = SessionState.Starting(username)
    }

    /**
     * 处理连接事件。
     *
     * @param event 连接事件
     */
    private suspend fun onConnectionEvent(event: ConnectionEvent) {
        val current = context ?: return
        when (event) {
            is ConnectionEvent.Connected -> {
                stepsJob?.cancel()
                stepsJob = scope.launch { runSteps(current, startIndex = 0, resumed = event.resumed) }
            }
            is ConnectionEvent.Stopped -> onSessionEnded(current, event.reason)
        }
    }

    /**
     * 会话被结束（协议规范 §2.4）：被挤下线、账号禁用、认证失败都清除保存的密码（保留工号供登录页回显），
     * 不再自动登录，需要用户手动重新登录（决策记录 A20）。
     *
     * 被挤下线也要清除：否则本机进程重启后会用保存的密码自动登录，反过来把另一台设备挤下线，两台设备互相踢。
     *
     * @param current 会话上下文
     * @param reason 原因
     */
    private suspend fun onSessionEnded(current: SessionContext, reason: DisconnectReason) {
        log.warn("会话结束：{}", reason)
        stepsJob?.cancel()
        credentialStore.clearPassword()
        connectionManager.stop()
        context = null
        pausedAt = null
        mutableState.value = SessionState.Ended(current.username, reason)
    }

    /**
     * 按顺序执行登录后的步骤。
     *
     * @param current 会话上下文
     * @param startIndex 从第几个步骤开始（改密后从暂停处继续）
     * @param resumed 是否流恢复（只执行 runOnResume 的步骤）
     */
    private suspend fun runSteps(current: SessionContext, startIndex: Int, resumed: Boolean) {
        log.info("开始执行登录后步骤：从第 {} 步开始，流恢复={}", startIndex + 1, resumed)
        val total = measureTimeMillis {
            for (index in startIndex until steps.size) {
                val step = steps[index]
                if (resumed && !step.runOnResume) continue
                val result = try {
                    var stepResult: StepResult
                    val cost = measureTimeMillis { stepResult = step.run(current) }
                    log.info("步骤 {}/{}「{}」完成，耗时 {}ms", index + 1, steps.size, step.name, cost)
                    stepResult
                } catch (e: CancellationException) {
                    throw e
                } catch (e: XmppException) {
                    if (e.isConnectionProblem()) {
                        // 连接断了：中止本轮，重连成功后会重新执行
                        log.warn("步骤「{}」因连接问题中止，等待重连后重新执行：{}", step.name, e.message)
                        return
                    }
                    log.warn("步骤「{}」失败，跳过：{}", step.name, e.message, e)
                    StepResult.Continue
                } catch (e: Exception) {
                    log.error("步骤「{}」出现未预期的错误，跳过", step.name, e)
                    StepResult.Continue
                }
                if (result == StepResult.MustChangePassword) {
                    log.info("需要修改初始密码，暂停后续步骤")
                    pausedAt = index + 1
                    mutableState.value = SessionState.MustChangePassword(current.username)
                    return
                }
            }
        }
        log.info("登录后步骤全部完成，耗时 {}ms", total)
        // 流恢复时如果还在强制改密页面，不改变状态
        if (mutableState.value !is SessionState.MustChangePassword) {
            mutableState.value = SessionState.Ready(current.username, current.capabilities)
        }
    }

    /**
     * 是否为连接问题（此时重连后会重新执行步骤）。
     *
     * @return 未连接、连接失败、超时时为 true
     */
    private fun XmppException.isConnectionProblem(): Boolean =
        this is XmppException.NotConnected || this is XmppException.ConnectionFailed || this is XmppException.Timeout

    private companion object {
        const val TAG = "SessionManager"
    }
}
