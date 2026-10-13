package cn.android666.im.core.xmpp.smack

import androidx.annotation.VisibleForTesting
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.LoginResult
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import java.net.InetAddress
import javax.net.ssl.X509TrustManager
import kotlin.system.measureTimeMillis

/**
 * [XmppClient] 的 Smack 实现（Android工程架构.md §4.2、§4.3）。
 *
 * - 必须使用 TLS（STARTTLS），只信任企业根证书（[trustManager]），按 XMPP 域名校验主机名；
 * - 只启用 SCRAM-SHA-1，禁止 PLAIN 等明文机制（协议规范 §2.1）；
 * - resource 固定为 `android`；启用流管理（XEP-0198）并请求流恢复（协议规范 §2.2 第 1、2 步）；
 * - 关闭 Smack 自带的自动重连，重连策略由 XmppConnectionManager 统一负责（开发计划 2.6）；
 * - 报文经 [XmppLogDebugger] 脱敏后写入 xmpp 日志。
 *
 * 所有阻塞调用都在 [DispatcherProvider.io] 上执行；login / reconnect / logout 用互斥锁串行化，
 * 避免重连与退出登录同时进行。
 *
 * @property serverConfig 服务器地址
 * @property trustManager 只信任企业根证书的 TrustManager（core.security.RootCaTrust）
 * @property dispatchers 协程调度器
 * @property clock 时间来源（报文日志的周期统计）
 * @property logStanzas 是否把报文写入 xmpp 日志，每次写入前判断，可运行时切换
 * @property stanzaLogSink 报文日志的输出，默认写入 xmpp 日志；测试时可替换以检查脱敏效果
 */
class SmackXmppClient(
    private val serverConfig: ServerConfig,
    private val trustManager: X509TrustManager,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val logStanzas: () -> Boolean,
    private val stanzaLogSink: (String) -> Unit = { Logs.xmpp(STANZA_TAG).info(it) },
) : XmppClient {

    /** 业务日志 */
    private val log = Logs.file(TAG)

    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = state.asStateFlow()

    /** 串行化登录、重连、退出 */
    private val mutex = Mutex()

    /** 当前连接；只在持有 [mutex] 时修改 */
    @Volatile
    private var connection: XMPPTCPConnection? = null

    /** 监听连接被动断开，更新状态 */
    private val connectionListener = object : ConnectionListener {
        override fun connectionClosedOnError(e: Exception) {
            val reason = SmackErrors.toDisconnectReason(e)
            log.warn("连接意外断开：{}，是否重连={}", reason, reason.shouldReconnect, e)
            state.value = ConnectionState.Disconnected(reason)
        }

        override fun connectionClosed() {
            log.info("连接已正常关闭")
        }
    }

    override suspend fun login(credential: Credential): LoginResult = mutex.withLock {
        withContext(dispatchers.io) {
            // 已有连接（例如切换账号）时先断开，避免同时存在两个连接
            connection?.let { old ->
                log.info("登录前断开旧连接")
                old.removeConnectionListener(connectionListener)
                runCatching { old.disconnect() }
            }
            connection = null
            state.value = ConnectionState.Connecting
            log.info("开始登录：工号={}，服务器={}", credential.username, serverConfig)

            val newConnection = XMPPTCPConnection(buildConfiguration(credential)).apply {
                setUseStreamManagement(true)
                setUseStreamManagementResumption(true)
                addConnectionListener(connectionListener)
                ReconnectionManager.getInstanceFor(this).disableAutomaticReconnection()
            }
            try {
                val cost = measureTimeMillis {
                    newConnection.connect()
                    newConnection.login()
                }
                connection = newConnection
                state.value = ConnectionState.Authenticated(resumed = false)
                log.info(
                    "登录成功：{}，耗时 {}ms，流管理={}",
                    newConnection.user,
                    cost,
                    newConnection.isSmEnabled,
                )
                LoginResult(resumed = false)
            } catch (e: Exception) {
                val error = SmackErrors.toXmppException(e)
                log.warn("登录失败：{}", error.message, e)
                newConnection.removeConnectionListener(connectionListener)
                runCatching { newConnection.disconnect() }
                state.value = ConnectionState.Disconnected(error.toDisconnectReason())
                throw error
            }
        }
    }

    override suspend fun reconnect(): LoginResult = mutex.withLock {
        withContext(dispatchers.io) {
            val current = connection ?: throw XmppException.NotConnected()
            if (current.isAuthenticated) {
                log.info("重连：连接仍然有效，无需重连")
                return@withContext LoginResult(resumed = true)
            }
            state.value = ConnectionState.Connecting
            log.info("开始重连，流恢复可用={}", current.isSmResumptionPossible)
            try {
                val cost = measureTimeMillis {
                    if (!current.isConnected) current.connect()
                    // 流恢复可用时 Smack 先尝试 resume，失败则自动用配置中的凭证完整重新登录
                    current.login()
                }
                val resumed = current.streamWasResumed()
                state.value = ConnectionState.Authenticated(resumed)
                log.info("重连成功：流恢复={}，耗时 {}ms", resumed, cost)
                LoginResult(resumed)
            } catch (e: Exception) {
                val error = SmackErrors.toXmppException(e)
                log.warn("重连失败：{}", error.message, e)
                runCatching { current.disconnect() }
                state.value = ConnectionState.Disconnected(error.toDisconnectReason())
                throw error
            }
        }
    }

    override suspend fun logout() = mutex.withLock {
        withContext(dispatchers.io) {
            val current = connection ?: return@withContext
            log.info("退出登录，断开连接")
            current.removeConnectionListener(connectionListener)
            // 正常断开会发送 </stream>，服务器立即释放会话，不再保留流恢复
            runCatching { current.disconnect() }
                .onFailure { log.warn("断开连接时出错", it) }
            connection = null
            state.value = ConnectionState.Idle
        }
    }

    /**
     * 模拟网络突然中断（不发送 `</stream>`，服务器会保留会话等待流恢复）。仅供集成测试使用。
     */
    @VisibleForTesting
    internal fun simulateNetworkDropForTest() {
        val current = connection ?: return
        current.instantShutdown()
        state.value = ConnectionState.Disconnected(DisconnectReason.Network(null))
    }

    /**
     * 构建 Smack 连接配置。
     *
     * @param credential 登录凭证
     * @return 连接配置
     */
    private fun buildConfiguration(credential: Credential): XMPPTCPConnectionConfiguration {
        val builder = XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(serverConfig.domain)
            .setPort(serverConfig.port)
            .setUsernameAndPassword(credential.username, credential.password)
            .setResource(serverConfig.resource)
            // 必须使用 TLS，服务器不支持 STARTTLS 时直接失败（协议规范 §2.1）
            .setSecurityMode(SecurityMode.required)
            // 只信任企业根证书；主机名按 XMPP 域名校验（Smack 默认的 XMPP 主机名校验器）
            .setCustomX509TrustManager(trustManager)
            // 只使用 SCRAM-SHA-1（协议规范 §2.1）
            .addEnabledSaslMechanism(SASL_MECHANISM)
            // 初始 presence 由 SessionManager 在获取 Roster 之后发送（协议规范 §2.2 第 8 步）
            .setSendPresence(false)
            .setDebuggerFactory(XmppLogDebugger.Factory(logStanzas, clock, stanzaLogSink))
        serverConfig.host?.let { builder.setHostAddress(InetAddress.getByName(it)) }
        return builder.build()
    }

    /**
     * 登录失败异常 → 连接状态中的断开原因。
     *
     * @return 断开原因
     */
    private fun XmppException.toDisconnectReason(): DisconnectReason = when (this) {
        is XmppException.AuthenticationFailed -> DisconnectReason.AuthenticationFailed
        is XmppException.Conflict -> DisconnectReason.Conflict
        is XmppException.AccountDisabled -> DisconnectReason.AccountDisabled
        else -> DisconnectReason.Network(this)
    }

    private companion object {
        const val TAG = "SmackXmppClient"

        /** 报文日志的标签 */
        const val STANZA_TAG = "Stanza"

        /** 唯一启用的 SASL 机制 */
        const val SASL_MECHANISM = "SCRAM-SHA-1"
    }
}
