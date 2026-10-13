package cn.android666.im.it

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.security.CredentialCipher
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.connection.XmppConnectionManager
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.data.account.CredentialStore
import cn.android666.im.data.session.SessionManager
import cn.android666.im.data.session.SessionState
import cn.android666.im.data.session.steps.AccountStatusStep
import cn.android666.im.data.session.steps.CapabilitiesStep
import cn.android666.im.data.session.steps.EnableCarbonsStep
import cn.android666.im.data.session.steps.InitialPresenceStep
import cn.android666.im.data.session.steps.ServerTimeStep
import cn.android666.im.testing.FakeNetworkMonitor
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [SessionManager] 集成测试（开发计划 2.13）：在真实服务器上执行完整的登录编排。
 * 使用测试账号 10008（接收方）和 10007（发送方）。
 */
class SessionManagerIT {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: SmackXmppClient

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        if (::client.isInitialized) runCatching { client.logout() }
        scope.cancel()
    }

    /** 完整登录：各步骤执行完成进入 Ready，能力、时钟、凭证都已就绪；发送初始 presence 后离线消息被投递 */
    @Test
    fun login_executesStepsAndReceivesOfflineMessageAfterPresence() = runBlocking {
        // 接收方离线时，发送方先发一条消息，服务器存为离线消息
        val body = "离线消息 ${UUID.randomUUID()}"
        ItServer.login(ItServer.user(7), resource = "pc").let { sender ->
            sender.sendStanza(
                MessageBuilder.buildMessage().to(ItServer.jid(ItServer.user(8))).ofType(Message.Type.chat).setBody(body).build(),
            )
            Thread.sleep(500)
            sender.disconnect()
        }

        client = SmackXmppClient(
            ServerConfig(ItServer.domain, ItServer.HOST, ItServer.PORT), ItServer.trustManager, dispatchers, SystemTimeClock, { false },
        )
        val serverClock = ServerClock(SystemTimeClock)
        val cipher = object : CredentialCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(encrypted: String) = encrypted
        }
        val store = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "s.preferences_pb") }, cipher)
        val session = SessionManager(
            client,
            XmppConnectionManager(client, FakeNetworkMonitor(), scope),
            store,
            {},
            listOf(
                EnableCarbonsStep(client),
                ServerTimeStep(client, serverClock),
                CapabilitiesStep(client),
                AccountStatusStep(client),
                InitialPresenceStep(client),
            ),
            scope,
        )

        session.login(Credential(ItServer.user(8), ItServer.testUserPassword))
        val ready = withTimeout(15_000) { session.state.first { it is SessionState.Ready } } as SessionState.Ready

        assertThat(ready.capabilities.mam).isTrue()
        assertThat(serverClock.isCalibrated).isTrue()
        assertThat(store.load()!!.username).isEqualTo(ItServer.user(8))
        // 验证第 8 步"发送初始 presence"：服务器收到 presence 后才把离线消息投递给 android 端并从离线存储中删除。
        // （SmackXmppClient 目前还不提供收消息接口，见开发计划 M4，所以从侧面验证：）
        // 同一账号的 pc 端随后登录时，如果离线消息还在存储中，会被投递给 pc 端；收不到说明已投递给 android 端
        val pc = ItServer.login(ItServer.user(8), resource = "pc")
        val inbox = LinkedBlockingQueue<Message>()
        ChatManager.getInstanceFor(pc).addIncomingListener { _, message, _ -> inbox.add(message) }
        val leftover = generateSequence { inbox.poll(2, TimeUnit.SECONDS) }.firstOrNull { it.body == body }
        pc.disconnect()
        assertThat(leftover).isNull()
    }
}
