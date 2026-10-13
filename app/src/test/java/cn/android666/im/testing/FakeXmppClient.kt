package cn.android666.im.testing

import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.LoginResult
import cn.android666.im.core.xmpp.OwnPresence
import cn.android666.im.core.xmpp.ServerCapabilities
import cn.android666.im.core.xmpp.ServerTime
import cn.android666.im.core.xmpp.UploadSlot
import cn.android666.im.core.xmpp.RosterItem
import cn.android666.im.core.xmpp.RosterSubscription
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.XmlNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    /** 最近一次 [setActive] 设置的状态 */
    var active = true
        private set

    override suspend fun setActive(active: Boolean) {
        calls += "active:$active"
        this.active = active
    }

    /** 最近一次发送的在线状态 */
    var lastPresence: OwnPresence? = null

    override suspend fun sendPresence(presence: OwnPresence) {
        calls += "presence"
        throwPendingError()
        lastPresence = presence
    }

    /** 已发送的 IQ 请求 */
    val sentIqs = mutableListOf<IqRequest<*>>()

    /**
     * IQ 应答器：根据请求返回结果子元素的 XML（null 表示空结果），或抛出 XmppException 模拟服务器错误。
     * 默认返回空结果。
     */
    var iqResponder: (IqRequest<*>) -> String? = { null }

    override suspend fun <T> sendIq(request: IqRequest<T>): T {
        calls += "iq:${request.elementName}"
        sentIqs += request
        val xml = iqResponder(request)
        // 与 SmackXmppClient 一致：结果格式错误转为 InvalidResponse
        return try {
            request.parseResult(xml?.let { XmlNode.parse(it) })
        } catch (e: IllegalArgumentException) {
            throw XmppException.InvalidResponse(e.message ?: request.elementName, e)
        }
    }

    /** 假的花名册（工号 JID → 条目） */
    val roster = linkedMapOf<String, RosterItem>()

    private val rosterPushFlow = MutableSharedFlow<RosterItem>(extraBufferCapacity = 64)

    override val rosterPushes: Flow<RosterItem> = rosterPushFlow

    override suspend fun loadRoster(): List<RosterItem> {
        calls += "roster:load"
        nextRequestError?.let { nextRequestError = null; throw it }
        return roster.values.toList()
    }

    /** 添加联系人：模拟服务器自动同意，花名册中为 TO 并推送 */
    override suspend fun addRosterItem(jid: String) {
        calls += "roster:add:$jid"
        nextRequestError?.let { nextRequestError = null; throw it }
        val item = RosterItem(jid, RosterSubscription.TO)
        roster[jid] = item
        rosterPushFlow.emit(item)
    }

    /** 移除联系人：从花名册删除并推送 REMOVE */
    override suspend fun removeRosterItem(jid: String) {
        calls += "roster:remove:$jid"
        nextRequestError?.let { nextRequestError = null; throw it }
        roster.remove(jid)
        rosterPushFlow.emit(RosterItem(jid, RosterSubscription.REMOVE))
    }

    /**
     * 模拟服务器推送花名册变化（例如本人在 PC 端添加了联系人）。
     *
     * @param item 条目
     */
    suspend fun pushRoster(item: RosterItem) {
        if (item.subscription == RosterSubscription.REMOVE) roster.remove(item.jid) else roster[item.jid] = item
        rosterPushFlow.emit(item)
    }

    /** 假的 vCard 头像（JID → 图片数据） */
    val avatars = mutableMapOf<String, ByteArray>()

    /** 本人的 JID（[setMyAvatar] 保存到 [avatars] 的键） */
    var selfJid = "10001@im.example.com"

    override suspend fun loadAvatar(jid: String): ByteArray? {
        calls += "avatar:load:$jid"
        throwPendingError()
        return avatars[jid]
    }

    override suspend fun setMyAvatar(data: ByteArray, mimeType: String) {
        calls += "avatar:set:$mimeType"
        throwPendingError()
        avatars[selfJid] = data
    }

    /** 申请过的上传槽位：文件名、大小、类型 */
    val slotRequests = mutableListOf<Triple<String, Long, String>>()

    /** 上传槽位的应答：根据文件名返回槽位，或抛出 XmppException。默认抛出 service-unavailable（没有上传服务） */
    var slotResponder: (filename: String, size: Long, contentType: String) -> UploadSlot = { _, _, _ ->
        throw XmppException.StanzaError("service-unavailable", null)
    }

    override suspend fun requestUploadSlot(filename: String, size: Long, contentType: String): UploadSlot {
        calls += "slot:$filename"
        slotRequests += Triple(filename, size, contentType)
        return slotResponder(filename, size, contentType)
    }

    private val incomingFlow = MutableSharedFlow<String>(extraBufferCapacity = 256)

    override val incomingMessages: Flow<String> = incomingFlow

    /**
     * 模拟收到一条 message 报文。
     *
     * @param xml message 报文
     */
    suspend fun receive(xml: String) {
        incomingFlow.emit(xml)
    }

    /**
     * 立即推送一条 message 报文（不挂起，可在 [iqResponder] 中调用，模拟 MAM 结果先于 IQ 结果推送）。
     *
     * @param xml message 报文
     */
    fun receiveNow(xml: String) {
        check(incomingFlow.tryEmit(xml)) { "收到的消息缓冲区已满" }
    }

    /** 已发送（已交给 [messageAckHandler] 处理）的 message 报文 */
    val sentMessages = mutableListOf<String>()

    /**
     * 发送消息时的服务器行为：返回 true 表示收到确认，false 表示服务器未启用流管理；
     * 可以 delay 模拟确认延迟、抛出 XmppException 模拟超时等。默认立即确认。
     */
    var messageAckHandler: suspend (xml: String, ackTimeoutMillis: Long) -> Boolean = { _, _ -> true }

    /** 与 SmackXmppClient 一致：未连接时抛出 NotConnected；先记录报文再等待确认 */
    override suspend fun sendMessage(xml: String, ackTimeoutMillis: Long): Boolean {
        require(XmlNode.parse(xml).attr("id") != null) { "message 必须带 id" }
        if (state.value !is ConnectionState.Authenticated) throw XmppException.NotConnected()
        sentMessages += xml
        return messageAckHandler(xml, ackTimeoutMillis)
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
