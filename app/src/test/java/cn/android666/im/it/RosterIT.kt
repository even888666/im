package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.RosterSubscription
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 常用联系人（花名册）集成测试（开发计划 3.19）：双方都在线时添加，服务器自动同意，
 * 对方客户端（订阅模式 manual）不会回绝；移除后双方花名册都更新。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*RosterIT*'`
 *
 * 使用测试账号 10014、10015。
 */
class RosterIT {

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    private val clients = mutableListOf<SmackXmppClient>()

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { runCatching { it.logout() } }
    }

    /**
     * 登录。
     *
     * @param index 测试账号序号
     * @return 客户端
     */
    private suspend fun login(index: Int): SmackXmppClient = SmackXmppClient(
        serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
        trustManager = ItServer.trustManager,
        dispatchers = dispatchers,
        clock = SystemTimeClock,
        logStanzas = { false },
    ).also {
        clients += it
        it.login(Credential(ItServer.user(index), ItServer.testUserPassword))
        it.sendPresence()
    }

    @Test
    fun addWhileBothOnline_autoApproved_thenRemove() = runBlocking {
        val a = login(14)
        val b = login(15)
        val jidA = "${ItServer.user(14)}@${ItServer.domain}"
        val jidB = "${ItServer.user(15)}@${ItServer.domain}"
        // 清理上次测试残留
        a.removeRosterItem(jidB)
        b.removeRosterItem(jidA)

        val becameContact = async(start = CoroutineStart.UNDISPATCHED) {
            a.rosterPushes.first { it.jid == jidB && it.subscription.isContact }
        }
        a.addRosterItem(jidB)
        withTimeout(10_000) { becameContact.await() }

        // 对方在线也不会回绝：稍等片刻后仍然是常用联系人
        Thread.sleep(500)
        assertThat(a.loadRoster().single { it.jid == jidB }.subscription.isContact).isTrue()
        assertThat(b.loadRoster().single { it.jid == jidA }.subscription).isAnyOf(RosterSubscription.FROM, RosterSubscription.BOTH)

        val removed = async(start = CoroutineStart.UNDISPATCHED) {
            a.rosterPushes.first { it.jid == jidB && it.subscription == RosterSubscription.REMOVE }
        }
        a.removeRosterItem(jidB)
        withTimeout(10_000) { removed.await() }
        assertThat(a.loadRoster().none { it.jid == jidB }).isTrue()
    }
}
