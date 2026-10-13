package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.account.AccountStatusRequest
import cn.android666.im.core.xmpp.extension.account.ChangePasswordRequest
import cn.android666.im.core.xmpp.extension.org.DeptChildrenRequest
import cn.android666.im.core.xmpp.extension.org.EmployeesRequest
import cn.android666.im.core.xmpp.extension.org.OrgSyncEntry
import cn.android666.im.core.xmpp.extension.org.OrgSyncRequest
import cn.android666.im.core.xmpp.extension.org.SearchEmployeesRequest
import cn.android666.im.core.xmpp.extension.presence.PresenceQueryRequest
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItAdmin
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.packet.RosterPacket
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.jxmpp.jid.impl.JidCreate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 组织架构插件集成测试（开发计划 3.1 ~ 3.11）：连接本地 Openfire（server/up.sh 部署并导入了
 * server/testdata/employees.csv），用 App 的 SmackXmppClient 和报文类验证协议规范 §3 ~ §5。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*OrgPluginIT*'`
 *
 * 测试账号分配（互不干扰，测试可重复运行）：
 * 10011 查询；10013 / 10014 在线状态；10016 / 10017 订阅；10018 强制改密；10019 弱密码；10020 禁用。
 */
class OrgPluginIT {

    /** JVM 上没有 Android 主线程，Main 也用 IO 代替 */
    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    /** 本测试创建的客户端，结束时统一退出 */
    private val clients = mutableListOf<SmackXmppClient>()

    /** 本测试创建的 Smack 原生连接，结束时统一断开 */
    private val connections = mutableListOf<XMPPTCPConnection>()

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { runCatching { it.logout() } }
        connections.forEach { runCatching { it.disconnect() } }
    }

    /**
     * 用 App 的客户端登录。
     *
     * @param index 测试账号序号
     * @param password 密码，默认测试密码
     * @return 已登录的客户端
     */
    private suspend fun login(index: Int, password: String = ItServer.testUserPassword): SmackXmppClient {
        val client = SmackXmppClient(
            serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
            trustManager = ItServer.trustManager,
            dispatchers = dispatchers,
            clock = SystemTimeClock,
            logStanzas = { false },
        ).also { clients += it }
        client.login(Credential(ItServer.user(index), password))
        return client
    }

    /**
     * 测试账号的 bare JID 字符串。
     *
     * @param index 测试账号序号
     * @return JID
     */
    private fun jid(index: Int) = "${ItServer.user(index)}@${ItServer.domain}"

    /** 3.1：disco#info 中有 account、org、presence 三个 feature（协议规范 §1.7） */
    @Test
    fun disco_orgFeatures() {
        val connection = ItServer.login(ItServer.user(11)).also { connections += it }
        val info = ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(JidCreate.domainBareFrom(ItServer.domain))
        assertThat(info.containsFeature("urn:android666:im:account:1")).isTrue()
        assertThat(info.containsFeature("urn:android666:im:org:1")).isTrue()
        assertThat(info.containsFeature("urn:android666:im:presence:1")).isTrue()
    }

    /** 3.7：全量同步（分页拉完）包含测试数据中的部门和员工；随后用新版本号增量同步为空 */
    @Test
    fun sync_fullPagedThenIncrementalEmpty() = runBlocking {
        val client = login(11)
        val entries = mutableListOf<OrgSyncEntry>()
        var after: String? = null
        var version: Long? = null
        for (pageNo in 0..MAX_PAGES) {
            check(pageNo < MAX_PAGES) { "同步分页超过 $MAX_PAGES 页，可能是游标没有前进" }
            val page = client.sendIq(OrgSyncRequest(version = 0, max = 7, after = after))
            assertThat(page.full).isTrue()
            if (version == null) version = page.version
            if (page.entries.isEmpty()) break
            entries += page.entries
            after = page.rsm?.last
        }
        val departments = entries.filterIsInstance<OrgSyncEntry.DepartmentUpsert>().map { it.department }
        val employees = entries.filterIsInstance<OrgSyncEntry.EmployeeUpsert>().map { it.employee }
        assertThat(departments.map { it.name }).containsAtLeast("总部", "研发中心", "Android组", "分公司")
        val zeng = employees.single { it.empNo == ItServer.user(10) }
        assertThat(zeng.name).isEqualTo("曾小明")
        assertThat(zeng.pinyin).isEqualTo("zengxiaoming")
        assertThat(zeng.jid).isEqualTo(jid(10))
        assertThat(employees.map { it.empNo }).containsAtLeast(ItServer.user(1), ItServer.user(19))
        // 部门在员工前面（客户端先写部门）
        assertThat(entries.indexOfFirst { it is OrgSyncEntry.EmployeeUpsert })
            .isGreaterThan(entries.indexOfLast { it is OrgSyncEntry.DepartmentUpsert })

        val incremental = client.sendIq(OrgSyncRequest(version = version!!))
        assertThat(incremental.full).isFalse()
        assertThat(incremental.version).isAtLeast(version)
    }

    /** 3.5：顶级部门、部门成员 */
    @Test
    fun deptChildren() = runBlocking {
        val client = login(11)
        val top = client.sendIq(DeptChildrenRequest(""))
        assertThat(top.departments.map { it.name }).containsAtLeast("总部", "分公司")

        val hq = top.departments.first { it.name == "总部" }
        val hqChildren = client.sendIq(DeptChildrenRequest(hq.id))
        val finance = hqChildren.departments.first { it.name == "财务部" }
        assertThat(finance.memberCount).isEqualTo(1)
        val members = client.sendIq(DeptChildrenRequest(finance.id))
        assertThat(members.employees.map { it.name }).containsExactly("曾小明")
        assertThat(members.rsm?.count).isEqualTo(1)
    }

    /** 3.6：批量员工详情，不存在的返回 delete */
    @Test
    fun employees_batch(): Unit = runBlocking {
        val client = login(11)
        val result = client.sendIq(EmployeesRequest(listOf(jid(1), "nobody@${ItServer.domain}")))
        assertThat(result.employees.single().name).isEqualTo("张三")
        assertThat(result.deletedJids).containsExactly("nobody@${ItServer.domain}")
    }

    /** 3.8：按首字母、部门名称搜索 */
    @Test
    fun search(): Unit = runBlocking {
        val client = login(11)
        assertThat(client.sendIq(SearchEmployeesRequest("zxm")).map { it.name }).contains("曾小明")
        assertThat(client.sendIq(SearchEmployeesRequest("上海分公司")).map { it.empNo })
            .containsExactly(ItServer.user(16), ItServer.user(17))
    }

    /** 3.11：在线状态查询：在线的端与自定义状态；离线 */
    @Test
    fun presenceQuery() = runBlocking {
        val online = ItServer.login(ItServer.user(13), resource = "pc").also { connections += it }
        // 自定义状态：忙碌（开会中），协议规范 §5.2
        val presence = online.stanzaFactory.buildPresenceStanza()
            .setMode(Presence.Mode.dnd)
            .setStatus("开会")
            .addExtension(StandardExtensionElement.builder("state", "urn:android666:im:presence:1").addAttribute("code", "meeting").build())
            .build()
        online.sendStanza(presence)

        val client = login(14)
        // presence 是异步处理的，稍等服务器记录
        var items = client.sendIq(PresenceQueryRequest(listOf(jid(13), "nobody@${ItServer.domain}")))
        for (i in 0 until 20) {
            if (items[0].resources.singleOrNull()?.code == "meeting") break
            Thread.sleep(100)
            items = client.sendIq(PresenceQueryRequest(listOf(jid(13), "nobody@${ItServer.domain}")))
        }
        val resource = items[0].resources.single()
        assertThat(items[0].online).isTrue()
        assertThat(resource.resource).isEqualTo("pc")
        assertThat(resource.show).isEqualTo("dnd")
        assertThat(resource.status).isEqualTo("开会")
        assertThat(resource.code).isEqualTo("meeting")
        assertThat(items[1].online).isFalse()
    }

    /** 3.10：订阅请求由服务器自动同意，对方离线也立即生效（协议规范 §5.1） */
    @Test
    fun subscription_autoApproved_contactOffline() {
        val requester = ItServer.login(ItServer.user(16)).also { connections += it }
        val roster = Roster.getInstanceFor(requester)
        roster.reloadAndWait()
        val contact = JidCreate.entityBareFrom(jid(17))
        roster.getEntry(contact)?.let { roster.removeEntry(it) }

        roster.createItemAndRequestSubscription(contact, null, null)
        var type: RosterPacket.ItemType? = null
        for (i in 0 until 50) {
            type = roster.getEntry(contact)?.type
            if (type == RosterPacket.ItemType.to || type == RosterPacket.ItemType.both) break
            Thread.sleep(100)
        }
        assertThat(type).isAnyOf(RosterPacket.ItemType.to, RosterPacket.ItemType.both)

        // 对方的花名册中是 from（被订阅），不需要对方操作
        val other = ItServer.login(ItServer.user(17)).also { connections += it }
        val otherRoster = Roster.getInstanceFor(other)
        otherRoster.reloadAndWait()
        val back = otherRoster.getEntry(JidCreate.entityBareFrom(jid(16)))
        assertThat(back?.type).isAnyOf(RosterPacket.ItemType.from, RosterPacket.ItemType.both)

        // 清理，保证测试可重复运行
        roster.removeEntry(roster.getEntry(contact))
        otherRoster.getEntry(JidCreate.entityBareFrom(jid(16)))?.let { otherRoster.removeEntry(it) }
    }

    /**
     * 3.2：管理员要求改密后，账号状态为 mustChangePassword；改密前发消息被拒绝（must-change-password）；
     * 改密成功后标记清除。
     */
    @Test
    fun mustChangePassword_flow() = runBlocking {
        ItAdmin.employeeAction(ItServer.user(18), "requireChange")
        val client = login(18)
        assertThat(client.sendIq(AccountStatusRequest()).mustChangePassword).isTrue()

        // 发消息被拒绝
        val raw = ItServer.login(ItServer.user(18), resource = "pc").also { connections += it }
        val errors = LinkedBlockingQueue<Message>()
        raw.addAsyncStanzaListener({ errors += it as Message }) { it is Message && it.type == Message.Type.error }
        raw.sendStanza(
            raw.stanzaFactory.buildMessageStanza().to(JidCreate.entityBareFrom(jid(1))).ofType(Message.Type.chat)
                .setBody("hi").build(),
        )
        val error = errors.poll(10, TimeUnit.SECONDS)
        assertThat(error).isNotNull()
        assertThat(error!!.error.condition).isEqualTo(StanzaError.Condition.forbidden)
        assertThat(error.error.getExtension<StandardExtensionElement>("app-error", "urn:android666:im:error:1")
            ?.getAttributeValue("code")).isEqualTo("must-change-password")

        // 改密（改回原密码也算改密，满足规则即可），标记清除
        client.sendIq(ChangePasswordRequest(ItServer.user(18), ItServer.testUserPassword))
        assertThat(client.sendIq(AccountStatusRequest()).mustChangePassword).isFalse()
    }

    /** 3.2：新密码不满足规则时返回 not-acceptable + weak-password（协议规范 §3.2、§16） */
    @Test
    fun weakPassword_rejected() = runBlocking {
        val client = login(19)
        val e = runCatching { client.sendIq(ChangePasswordRequest(ItServer.user(19), "12345678")) }.exceptionOrNull()
        assertThat(e).isInstanceOf(XmppException.StanzaError::class.java)
        e as XmppException.StanzaError
        assertThat(e.condition).isEqualTo("not-acceptable")
        assertThat(e.appErrorCode).isEqualTo("weak-password")
        // 密码没有被修改
        client.logout()
        assertThat(login(19).sendIq(AccountStatusRequest()).mustChangePassword).isFalse()
    }

    /** 3.9：禁用账号立即踢下线（policy-violation + account-disabled），之后无法登录；启用后恢复 */
    @Test
    fun disable_kicksAndBlocksLogin_thenEnable() = runBlocking {
        try {
            val client = login(20)
            ItAdmin.employeeAction(ItServer.user(20), "disable")

            val state = withTimeout(10_000) { client.connectionState.first { it is ConnectionState.Disconnected } }
            assertThat((state as ConnectionState.Disconnected).reason).isEqualTo(DisconnectReason.AccountDisabled)

            val e = runCatching { login(20) }.exceptionOrNull()
            assertThat(e).isInstanceOf(XmppException.AuthenticationFailed::class.java)

            // 禁用的员工在批量查询中为已离职
            val other = login(11)
            assertThat(other.sendIq(EmployeesRequest(listOf(jid(20)))).deletedJids).containsExactly(jid(20))
        } finally {
            ItAdmin.employeeAction(ItServer.user(20), "enable")
        }
        assertThat(login(20).sendIq(AccountStatusRequest()).mustChangePassword).isFalse()
    }

    private companion object {
        /** 同步分页的页数上限（测试数据只有几十条，每页 7 条） */
        const val MAX_PAGES = 50
    }
}
