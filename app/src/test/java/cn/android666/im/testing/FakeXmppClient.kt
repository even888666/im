package cn.android666.im.testing

import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.LoginResult
import cn.android666.im.core.xmpp.ServerCapabilities
import cn.android666.im.core.xmpp.ServerTime
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [XmppClient] 的测试替身（Android工程架构.md §9.2"优先 Fake，少用 Mock"）。
 *
 * - 通过 [loginResults] / [reconnectResults] 预设每次调用的结果（成功或抛出的异常），未预设时默认成功；
 * - 通过 [simulateDisconnect] 模拟服务器断开连接；
 * - [calls] 记录调用顺序，便于断言。
 */
class FakeXmppClient : XmppClient {

    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = state

    /** 依次用于每次 [login] 的结果：LoginResult 表示成功，XmppException 表示失败 */
    val loginResults = ArrayDeque<Any>()

    /** 依次用于每次 [reconnect] 的结果：LoginResult 表示成功，XmppException 表示失败 */
    val reconnectResults = ArrayDeque<Any>()

    /** 调用记录，例如 `login:10001`、`reconnect`、`logout` */
    val calls = mutableListOf<String>()

    /** 最近一次登录使用的凭证 */
    var lastCredential: Credential? = null
        private set

    override suspend fun login(credential: Credential): LoginResult {
        calls += "login:${credential.username}"
        lastCredential = credential
        return complete(loginResults.removeFirstOrNull() ?: LoginResult(resumed = false))
    }

    override suspend fun reconnect(): LoginResult {
        calls += "reconnect"
        if (lastCredential == null) throw XmppException.NotConnected()
        return complete(reconnectResults.removeFirstOrNull() ?: LoginResult(resumed = true))
    }

    /** [discoverCapabilities] 返回的能力，默认支持协议规范 §1.7 的全部 feature、上传上限 100 MB */
    var capabilities = ServerCapabilities(ServerCapabilities.EXPECTED.toSet(), maxUploadFileSize = 104_857_600L)

    /** [queryServerTime] 返回的结果 */
    var serverTime = ServerTime(serverMillis = 1_000_000L, requestSentAtMillis = 1_000_000L, roundTripMillis = 0L)

    /** 下一次 [enableCarbons] / [queryServerTime] / [discoverCapabilities] 调用要抛出的异常（只生效一次） */
    var nextRequestError: XmppException? = null

    override suspend fun enableCarbons() {
        calls += "enableCarbons"
        throwPendingError()
    }

    override suspend fun queryServerTime(): ServerTime {
        calls += "queryServerTime"
        throwPendingError()
        return serverTime
    }

    override suspend fun discoverCapabilities(): ServerCapabilities {
        calls += "discoverCapabilities"
        throwPendingError()
        return capabilities
    }

    /** 抛出并清除预设的请求错误 */
    private fun throwPendingError() {
        nextRequestError?.let {
            nextRequestError = null
            throw it
        }
    }

    /** 依次用于每次 [checkAlive] 的结果，未预设时返回 true；返回 false 时模拟连接失效并断开 */
    val aliveResults = ArrayDeque<Boolean>()

    override suspend fun checkAlive(): Boolean {
        calls += "checkAlive"
        val alive = aliveResults.removeFirstOrNull() ?: true
        if (!alive) state.value = ConnectionState.Disconnected(DisconnectReason.Network(null))
        return alive
    }

    override suspend fun logout() {
        calls += "logout"
        state.value = ConnectionState.Idle
    }

    /**
     * 模拟连接被断开。
     *
     * @param reason 断开原因
     */
    fun simulateDisconnect(reason: DisconnectReason) {
        state.value = ConnectionState.Disconnected(reason)
    }

    /**
     * 按预设结果完成一次登录 / 重连，并更新连接状态。
     *
     * @param result 预设结果
     * @return 成功时的登录结果
     */
    private fun complete(result: Any): LoginResult {
        state.value = ConnectionState.Connecting
        when (result) {
            is LoginResult -> {
                state.value = ConnectionState.Authenticated(result.resumed)
                return result
            }
            is XmppException -> {
                state.value = ConnectionState.Disconnected(
                    when (result) {
                        is XmppException.AuthenticationFailed -> DisconnectReason.AuthenticationFailed
                        is XmppException.Conflict -> DisconnectReason.Conflict
                        is XmppException.AccountDisabled -> DisconnectReason.AccountDisabled
                        else -> DisconnectReason.Network(result)
                    },
                )
                throw result
            }
            else -> error("不支持的预设结果：$result")
        }
    }
}
