package cn.android666.im.core.xmpp.smack

import androidx.annotation.VisibleForTesting
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.LoginResult
import cn.android666.im.core.xmpp.OwnPresence
import cn.android666.im.core.xmpp.RosterItem
import cn.android666.im.core.xmpp.RosterSubscription
import cn.android666.im.core.xmpp.ServerCapabilities
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.ServerTime
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.XmlNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.filter.StanzaTypeFilter
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterEntry
import org.jivesoftware.smack.roster.RosterListener
import org.jivesoftware.smack.roster.packet.RosterPacket
import org.jivesoftware.smack.sm.predicates.Predicate
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.carbons.CarbonManager
import org.jivesoftware.smackx.csi.ClientStateIndicationManager
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.ping.PingManager
import org.jivesoftware.smackx.time.EntityTimeManager
import org.jivesoftware.smackx.vcardtemp.VCardManager
import org.jivesoftware.smackx.vcardtemp.packet.VCard
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
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
 * - 心跳：60 秒没有收到任何数据时发送 XEP-0199 ping，10 秒内无响应视为连接失效，立即断开并交给重连逻辑；
 * - 流管理确认：每发送一条消息或每 5 个 stanza 请求一次 `<r/>`（协议规范 §2.3）；
 * - 前后台：[setActive] 发送 XEP-0352 CSI，新流建立后自动补发非活跃状态；
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

    /** 期望的 CSI 状态：true 为活跃（前台）。默认活跃，与服务器对新流的默认值一致 */
    @Volatile
    private var desiredActive = true

    /** 当前连接；只在持有 [mutex] 时修改 */
    @Volatile
    private var connection: XMPPTCPConnection? = null

    /** 花名册推送（跨越重连持续有效） */
    private val rosterPushFlow = MutableSharedFlow<RosterItem>(extraBufferCapacity = ROSTER_PUSH_BUFFER)

    override val rosterPushes: Flow<RosterItem> = rosterPushFlow.asSharedFlow()

    /**
     * 收到的 message 报文。缓冲区不设上限：Smack 的监听线程只管放入、从不阻塞，订阅者按顺序处理
     * （单次 MAM 补拉最多 100 条，实际不会积压太多）。
     */
    private val incomingMessageFlow = MutableSharedFlow<String>(extraBufferCapacity = Int.MAX_VALUE)

    override val incomingMessages: Flow<String> = incomingMessageFlow.asSharedFlow()

    /** 收到 message 时转为 XML 发出（同步监听器：按到达顺序在单个线程中回调） */
    private val messageListener = StanzaListener { stanza ->
        val xml = SmackMessages.toXml(stanza as Message)
        if (!incomingMessageFlow.tryEmit(xml)) log.warn("收到的消息无法放入缓冲区，丢弃：id={}", stanza.stanzaId)
    }

    /**
     * Smack 花名册的变化回调（在 Smack 的线程中调用）。登录时加载花名册也会回调 entriesAdded，转发后由业务层幂等处理。
     */
    private val rosterListener = object : RosterListener {
        override fun entriesAdded(addresses: Collection<Jid>) = emitRosterEntries(addresses)

        override fun entriesUpdated(addresses: Collection<Jid>) = emitRosterEntries(addresses)

        override fun entriesDeleted(addresses: Collection<Jid>) {
            addresses.forEach { emitRosterPush(RosterItem(it.asBareJid().toString(), RosterSubscription.REMOVE)) }
        }

        override fun presenceChanged(presence: Presence) {
            // 在线状态按需查询（协议规范 §5.3），不处理订阅推送的 presence
        }
    }

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
                configureKeepAlive(this)
                configureRoster(this)
                addSyncStanzaListener(messageListener, StanzaTypeFilter.MESSAGE)
            }
            try {
                val cost = measureTimeMillis {
                    newConnection.connect()
                    newConnection.login()
                }
                connection = newConnection
                applyClientState(newConnection, resumed = false)
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
                applyClientState(current, resumed)
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

    override suspend fun enableCarbons() = request("启用 Carbons") { current ->
        CarbonManager.getInstanceFor(current).enableCarbons()
    }

    override suspend fun queryServerTime(): ServerTime = request("查询服务器时间") { current ->
        val sentAt = clock.nowMillis()
        val startElapsed = clock.elapsedMillis()
        val time = EntityTimeManager.getInstanceFor(current).getTime(current.xmppServiceDomain)
        ServerTime(
            serverMillis = time.zonedDateTime.toInstant().toEpochMilli(),
            requestSentAtMillis = sentAt,
            roundTripMillis = clock.elapsedMillis() - startElapsed,
        )
    }

    override suspend fun discoverCapabilities(): ServerCapabilities = request("能力发现") { current ->
        val info = ServiceDiscoveryManager.getInstanceFor(current).discoverInfo(current.xmppServiceDomain)
        val features = info.features.map { it.`var` }.toSet()
        // 文件上传服务是独立的子域，需要另外发现；没有上传服务不影响登录
        val upload = HttpFileUploadManager.getInstanceFor(current)
        val maxFileSize = runCatching { if (upload.discoverUploadService()) upload.defaultUploadService.maxFileSize else null }
            .onFailure { log.warn("发现文件上传服务失败", it) }
            .getOrNull()
        ServerCapabilities(features, maxFileSize)
    }

    override suspend fun setActive(active: Boolean) {
        desiredActive = active
        withContext(dispatchers.io) {
            val current = connection?.takeIf { it.isAuthenticated } ?: return@withContext
            sendClientState(current, active)
        }
    }

    /**
     * 新流建立后按期望状态补发 CSI：流恢复时服务器保留了原来的状态，不需要补发；
     * 新流默认为活跃，只有期望为非活跃时才需要发送。
     *
     * @param target 刚认证的连接
     * @param resumed 是否流恢复
     */
    private fun applyClientState(target: XMPPTCPConnection, resumed: Boolean) {
        if (!resumed && !desiredActive) sendClientState(target, active = false)
    }

    /**
     * 发送 CSI 状态；服务器不支持或发送失败时只记录日志（不影响连接）。
     *
     * @param target 连接
     * @param active 是否活跃
     */
    private fun sendClientState(target: XMPPTCPConnection, active: Boolean) {
        if (!ClientStateIndicationManager.isSupported(target)) {
            log.info("服务器不支持 CSI，忽略前后台状态")
            return
        }
        try {
            if (active) ClientStateIndicationManager.active(target) else ClientStateIndicationManager.inactive(target)
            log.info("已发送 CSI：{}", if (active) "active（前台）" else "inactive（后台）")
        } catch (e: Exception) {
            log.warn("发送 CSI 失败", e)
        }
    }

    override suspend fun sendPresence(presence: OwnPresence) = request("发送 presence") { current ->
        val builder = current.stanzaFactory.buildPresenceStanza().ofType(Presence.Type.available)
        when (presence.show) {
            "away" -> builder.setMode(Presence.Mode.away)
            "dnd" -> builder.setMode(Presence.Mode.dnd)
            "xa" -> builder.setMode(Presence.Mode.xa)
            "chat" -> builder.setMode(Presence.Mode.chat)
        }
        presence.status?.let { builder.setStatus(it) }
        presence.code?.let {
            builder.addExtension(StandardExtensionElement.builder("state", OwnPresence.NAMESPACE).addAttribute("code", it).build())
        }
        current.sendStanza(builder.build())
        log.info("已发送 presence：{}", presence)
    }

    override suspend fun <T> sendIq(request: IqRequest<T>): T = request("IQ ${request.namespace}#${request.elementName}") { current ->
        // 发给本人账号的请求不带 to（例如 MAM 本人归档，带服务器域名会被当作查询服务器的归档而拒绝）
        val iq = RawIq(request).apply { to = if (request.toOwnAccount) null else JidCreate.from(request.to ?: serverConfig.domain) }
        // 先注册结果解析器，保证结果中嵌套元素的命名空间不丢失
        RawResultIqProvider.registerFor(request)
        val response: IQ = current.sendIqRequestAndWaitForResponse(iq)
        val child = resultChildXml(response)
        val node = try {
            child?.let { XmlNode.parse(it) }
        } catch (e: IllegalArgumentException) {
            throw XmppException.InvalidResponse("${request.elementName} 结果无法解析", e)
        }
        try {
            request.parseResult(node)
        } catch (e: IllegalArgumentException) {
            throw XmppException.InvalidResponse(e.message ?: request.elementName, e)
        }
    }

    override suspend fun loadRoster(): List<RosterItem> = request("获取花名册") { current ->
        val roster = Roster.getInstanceFor(current)
        if (!roster.isLoaded) roster.reloadAndWait()
        roster.entries.map { it.toRosterItem() }.also { log.info("花名册共 {} 条", it.size) }
    }

    override suspend fun addRosterItem(jid: String) = request("添加常用联系人") { current ->
        // 名称留空：显示名一律取组织架构姓名（协议规范 §5.1）
        Roster.getInstanceFor(current).createItemAndRequestSubscription(JidCreate.entityBareFrom(jid), null, null)
        log.info("已添加常用联系人：{}", jid)
    }

    override suspend fun removeRosterItem(jid: String) = request("移除常用联系人") { current ->
        val roster = Roster.getInstanceFor(current)
        val entry = roster.getEntry(JidCreate.entityBareFrom(jid))
        if (entry != null) {
            roster.removeEntry(entry)
            log.info("已移除常用联系人：{}", jid)
        }
    }

    /**
     * 配置花名册：订阅请求一律不自动处理（manual）。服务器会自动同意本域用户的订阅（协议规范 §5.1），
     * 如果客户端按 Smack 默认的 reject_all 回复 unsubscribed，会把服务器刚同意的订阅取消掉。
     *
     * @param target 连接
     */
    private fun configureRoster(target: XMPPTCPConnection) {
        Roster.getInstanceFor(target).apply {
            subscriptionMode = Roster.SubscriptionMode.manual
            addRosterListener(rosterListener)
        }
    }

    /**
     * 把花名册中指定 JID 的当前状态转发为推送。
     *
     * @param addresses 变化的 JID
     */
    private fun emitRosterEntries(addresses: Collection<Jid>) {
        val roster = connection?.let { Roster.getInstanceFor(it) } ?: return
        addresses.forEach { address ->
            roster.getEntry(address.asBareJid())?.let { emitRosterPush(it.toRosterItem()) }
        }
    }

    /**
     * 发出一条花名册推送；缓冲区满时丢弃并记录（推送只用于实时更新，下次登录会重新获取完整花名册）。
     *
     * @param item 条目
     */
    private fun emitRosterPush(item: RosterItem) {
        if (!rosterPushFlow.tryEmit(item)) log.warn("花名册推送缓冲区已满，丢弃：{}", item)
    }

    override suspend fun loadAvatar(jid: String): ByteArray? = request("读取头像") { current ->
        try {
            VCardManager.getInstanceFor(current).loadVCard(JidCreate.entityBareFrom(jid)).avatar?.takeIf { it.isNotEmpty() }
        } catch (e: XMPPException.XMPPErrorException) {
            // 没有 vCard：Openfire 返回 service-unavailable（XEP-0054 也允许 item-not-found）
            if (e.isNoVCard()) null else throw e
        }
    }

    override suspend fun setMyAvatar(data: ByteArray, mimeType: String) = request("设置头像") { current ->
        val manager = VCardManager.getInstanceFor(current)
        val vCard = try {
            manager.loadVCard()
        } catch (e: XMPPException.XMPPErrorException) {
            if (e.isNoVCard()) VCard() else throw e
        }
        vCard.setAvatar(data, mimeType)
        manager.saveVCard(vCard)
        log.info("已设置头像：{} 字节", data.size)
    }

    override suspend fun sendMessage(xml: String, ackTimeoutMillis: Long): Boolean {
        val message = SmackMessages.build(xml)
        val id = message.stanzaId
        val current = connection?.takeIf { it.isAuthenticated } ?: throw XmppException.NotConnected()
        val acked = CompletableDeferred<Unit>()
        val smEnabled = current.isSmEnabled
        withContext(dispatchers.io) {
            try {
                // 先注册确认监听再发送，避免确认先于注册到达
                if (smEnabled) current.addStanzaIdAcknowledgedListener(id) { acked.complete(Unit) }
                current.sendStanza(message)
            } catch (e: Exception) {
                if (smEnabled) current.removeStanzaIdAcknowledgedListener(id)
                val error = SmackErrors.toXmppException(e)
                log.warn("发送消息失败：id={}，{}", id, error.message)
                throw error
            }
        }
        if (!smEnabled) {
            log.warn("服务器未启用流管理，无法确认消息：id={}", id)
            return false
        }
        val start = clock.elapsedMillis()
        val ok = withTimeoutOrNull(ackTimeoutMillis) { acked.await() } != null
        if (!ok) {
            current.removeStanzaIdAcknowledgedListener(id)
            log.warn("消息 {}ms 内未收到服务器确认：id={}", ackTimeoutMillis, id)
            throw XmppException.Timeout()
        }
        log.debug("消息已确认：id={}，耗时 {}ms", id, clock.elapsedMillis() - start)
        return true
    }

    override suspend fun checkAlive(): Boolean = withContext(dispatchers.io) {
        val current = connection
        if (current == null || !current.isAuthenticated) return@withContext false
        val alive = try {
            PingManager.getInstanceFor(current).pingMyServer(false, PING_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            log.warn("连接检测出错", e)
            false
        }
        if (alive) {
            log.info("连接检测：可用")
        } else {
            onConnectionDead(current, "主动检测无响应")
        }
        alive
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
     * 在已认证的连接上执行一次请求：切到 IO 线程、记录耗时、把 Smack 异常映射为 [XmppException]。
     *
     * @param action 操作名称，写日志用
     * @param block 使用连接执行的阻塞操作
     * @return 操作结果
     * @throws XmppException 未连接或操作失败
     */
    private suspend fun <T> request(action: String, block: (XMPPTCPConnection) -> T): T = withContext(dispatchers.io) {
        val current = connection?.takeIf { it.isAuthenticated } ?: throw XmppException.NotConnected()
        val start = clock.elapsedMillis()
        try {
            block(current).also { log.info("{}成功，耗时 {}ms", action, clock.elapsedMillis() - start) }
        } catch (e: Exception) {
            val error = SmackErrors.toXmppException(e)
            log.warn("{}失败，耗时 {}ms：{}", action, clock.elapsedMillis() - start, error.message, e)
            throw error
        }
    }

    /**
     * 配置心跳与流管理确认。
     *
     * @param target 新创建的连接
     */
    private fun configureKeepAlive(target: XMPPTCPConnection) {
        // IQ 响应超时 10 秒（协议规范 §2.3：ping 10 秒内无响应视为断线），也作为其他请求的默认超时
        target.replyTimeout = PING_TIMEOUT_MILLIS
        PingManager.getInstanceFor(target).apply {
            // Smack 只在该间隔内没有收到任何数据时才发 ping，与协议"60 秒无收发数据时发送"一致
            pingInterval = PING_INTERVAL_SECONDS
            registerPingFailedListener { onConnectionDead(target, "心跳无响应") }
        }
        // 每发送一条消息或每 5 个 stanza 请求一次确认，消息的"已发送"状态更及时（协议规范 §2.3）
        target.addRequestAckPredicate(Predicate.forMessagesOrAfter5Stanzas())
    }

    /**
     * 连接已失效（心跳或主动检测无响应）：立即断开，状态改为网络原因断开，由 XmppConnectionManager 重连。
     * 使用 instantShutdown（不发送 `</stream>`），服务器会保留会话，重连时可以流恢复。
     *
     * @param target 失效的连接
     * @param why 原因，写日志用
     */
    private fun onConnectionDead(target: XMPPTCPConnection, why: String) {
        // 回调可能来自旧连接（已被替换），只处理当前连接
        if (connection !== target) return
        log.warn("连接失效（{}），断开并等待重连", why)
        runCatching { target.instantShutdown() }
        state.value = ConnectionState.Disconnected(DisconnectReason.Network(XmppException.Timeout()))
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
     * 是否为"没有 vCard"的错误：Openfire 对没有 vCard 的用户返回 service-unavailable，XEP-0054 也允许 item-not-found。
     *
     * @return 是则为 true
     */
    private fun XMPPException.XMPPErrorException.isNoVCard(): Boolean =
        stanzaError.condition == StanzaError.Condition.item_not_found ||
            stanzaError.condition == StanzaError.Condition.service_unavailable

    /**
     * Smack 花名册条目转为与 Smack 无关的模型。
     *
     * @return 花名册条目
     */
    private fun RosterEntry.toRosterItem(): RosterItem = RosterItem(
        jid = jid.asBareJid().toString(),
        subscription = when (type) {
            RosterPacket.ItemType.to -> RosterSubscription.TO
            RosterPacket.ItemType.from -> RosterSubscription.FROM
            RosterPacket.ItemType.both -> RosterSubscription.BOTH
            RosterPacket.ItemType.remove -> RosterSubscription.REMOVE
            else -> RosterSubscription.NONE
        },
    )

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

        /** 心跳间隔（秒）：60 秒无收发数据时发送 ping（协议规范 §2.3） */
        const val PING_INTERVAL_SECONDS = 60

        /** ping 及其他 IQ 的响应超时（毫秒） */
        const val PING_TIMEOUT_MILLIS = 10_000L

        /** 唯一启用的 SASL 机制 */
        const val SASL_MECHANISM = "SCRAM-SHA-1"

        /** 花名册推送的缓冲条数（登录时加载花名册会一次推送全部条目） */
        const val ROSTER_PUSH_BUFFER = 1024
    }
}
