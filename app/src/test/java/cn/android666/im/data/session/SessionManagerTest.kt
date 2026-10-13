package cn.android666.im.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.security.CredentialCipher
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.LoginResult
import cn.android666.im.core.xmpp.ServerCapabilities
import cn.android666.im.core.xmpp.ServerTime
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.connection.ReconnectPolicy
import cn.android666.im.core.xmpp.connection.XmppConnectionManager
import cn.android666.im.core.xmpp.extension.account.AccountStatusRequest
import cn.android666.im.core.xmpp.extension.account.ChangePasswordRequest
import cn.android666.im.data.account.CredentialStore
import cn.android666.im.data.account.SavedLogin
import cn.android666.im.data.session.steps.AccountStatusStep
import cn.android666.im.data.session.steps.CapabilitiesStep
import cn.android666.im.data.session.steps.EnableCarbonsStep
import cn.android666.im.data.session.steps.InitialPresenceStep
import cn.android666.im.data.session.steps.ServerTimeStep
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeNetworkMonitor
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SessionManager] 测试：真实的 [XmppConnectionManager] 与 [CredentialStore]（临时文件），
 * 配合 [FakeXmppClient] 验证登录后步骤的编排（协议规范 §2.2）。
 *
 * 注意：被测对象的协程都运行在 backgroundScope 中，推进时必须用 runCurrent / advanceTimeBy，
 * 不能用 advanceUntilIdle（它判断"空闲"时不考虑 backgroundScope 中的任务，会直接返回）。
 */
class SessionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val client = FakeXmppClient()
    private val clock = FakeClock(startMillis = 1_000_000)
    private val serverClock = ServerClock(clock)
    private val loggedInUsers = mutableListOf<String>()

    /** 退出登录回调（关闭用户数据库）的调用次数 */
    private var loggedOutCount = 0

    /** 可逆的假加密 */
    private val cipher = object : CredentialCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    private lateinit var credentialStore: CredentialStore

    /** 账号状态 IQ 的应答：mustChangePassword 的值 */
    private var mustChangePassword = false

    /** 全部 IQ 的步骤（不含账号状态之外的 IQ） */
    private val baseCalls = listOf("enableCarbons", "queryServerTime", "discoverCapabilities", "iq:account", "presence")

    private fun TestScope.newSessionManager(): SessionManager {
        val file = File(tempFolder.root, "settings.preferences_pb")
        credentialStore = CredentialStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, cipher)
        client.iqResponder = { request ->
            when (request) {
                is AccountStatusRequest ->
                    "<account xmlns='urn:android666:im:account:1' mustChangePassword='$mustChangePassword'/>"
                else -> null
            }
        }
        val connectionManager = XmppConnectionManager(client, FakeNetworkMonitor(), backgroundScope) {
            ReconnectPolicy(jitterRatio = 0.0)
        }
        val steps = listOf(
            EnableCarbonsStep(client),
            ServerTimeStep(client, serverClock),
            CapabilitiesStep(client),
            AccountStatusStep(client),
            InitialPresenceStep(client),
        )
        return SessionManager(client, connectionManager, credentialStore, { loggedInUsers += it }, steps, backgroundScope) {
            loggedOutCount++
        }.also { runCurrent() }
    }

    private val credential = Credential("10001", "Passw0rd")

    @Test
    fun login_runsAllStepsInOrder_thenReady() = runTest {
        val manager = newSessionManager()
        client.serverTime = ServerTime(serverMillis = 1_005_000, requestSentAtMillis = 1_000_000, roundTripMillis = 0)

        manager.login(credential)
        runCurrent()

        assertThat(client.calls).containsExactlyElementsIn(listOf("login:10001") + baseCalls).inOrder()
        val state = manager.state.value as SessionState.Ready
        assertThat(state.username).isEqualTo("10001")
        assertThat(state.capabilities).isEqualTo(client.capabilities)
        // 服务器时间校准
        assertThat(serverClock.offsetMillis).isEqualTo(5_000)
        // 凭证保存、日志前缀切换
        assertThat(credentialStore.load()).isEqualTo(SavedLogin("10001", "Passw0rd"))
        assertThat(loggedInUsers).containsExactly("10001")
    }

    @Test
    fun login_failure_throwsAndLoggedOut() = runTest {
        val manager = newSessionManager()
        client.loginResults += XmppException.AuthenticationFailed()

        val e = runCatching { manager.login(credential) }.exceptionOrNull()
        runCurrent()

        assertThat(e).isInstanceOf(XmppException.AuthenticationFailed::class.java)
        assertThat(manager.state.value).isEqualTo(SessionState.LoggedOut)
        assertThat(credentialStore.load()).isNull()
    }

    /** 协议规范 §3.1：需要改密时暂停，不发送初始 presence；改密成功后继续剩余步骤 */
    @Test
    fun mustChangePassword_pausesThenContinuesAfterChange() = runTest {
        val manager = newSessionManager()
        mustChangePassword = true

        manager.login(credential)
        runCurrent()

        assertThat(manager.state.value).isEqualTo(SessionState.MustChangePassword("10001"))
        assertThat(client.calls).doesNotContain("presence")

        manager.changePassword("NewPassw0rd")
        runCurrent()

        val change = client.sentIqs.filterIsInstance<ChangePasswordRequest>().single()
        assertThat(change.toChildXml()).contains("<password>NewPassw0rd</password>")
        assertThat(credentialStore.load()!!.password).isEqualTo("NewPassw0rd")
        // 从暂停处继续：只执行了剩下的"发送初始 presence"
        assertThat(client.calls.last()).isEqualTo("presence")
        assertThat(client.calls.count { it == "iq:account" }).isEqualTo(1)
        assertThat(manager.state.value).isInstanceOf(SessionState.Ready::class.java)
    }

    /** 改密失败（密码太弱）：抛出带错误码的异常，仍停留在改密状态 */
    @Test
    fun changePassword_weak_throwsAndStaysPaused() = runTest {
        val manager = newSessionManager()
        mustChangePassword = true
        manager.login(credential)
        runCurrent()
        client.iqResponder = { throw XmppException.StanzaError("not-acceptable", "weak-password") }

        val e = runCatching { manager.changePassword("123") }.exceptionOrNull()

        assertThat((e as XmppException.StanzaError).appErrorCode).isEqualTo("weak-password")
        assertThat(manager.state.value).isEqualTo(SessionState.MustChangePassword("10001"))
        assertThat(credentialStore.load()!!.password).isEqualTo("Passw0rd")
    }

    /** 服务器没有组织架构插件：跳过账号状态查询 */
    @Test
    fun noAccountFeature_skipsAccountStatus() = runTest {
        val manager = newSessionManager()
        client.capabilities = ServerCapabilities(setOf(ServerCapabilities.MAM))

        manager.login(credential)
        runCurrent()

        assertThat(client.calls).doesNotContain("iq:account")
        assertThat(client.calls).contains("presence")
        assertThat(manager.state.value).isInstanceOf(SessionState.Ready::class.java)
    }

    /** 非连接类错误：跳过该步骤，继续后面的步骤 */
    @Test
    fun stepError_skippedAndContinue() = runTest {
        val manager = newSessionManager()
        client.nextRequestError = XmppException.StanzaError("service-unavailable", null) // Carbons 失败

        manager.login(credential)
        runCurrent()

        assertThat(client.calls).containsAtLeast("queryServerTime", "presence")
        assertThat(manager.state.value).isInstanceOf(SessionState.Ready::class.java)
    }

    /** 连接类错误：中止本轮；重连（未流恢复）后重新执行全部步骤 */
    @Test
    fun connectionError_abortsThenRerunsAfterReconnect() = runTest {
        val manager = newSessionManager()
        client.nextRequestError = XmppException.NotConnected()

        manager.login(credential)
        runCurrent()
        assertThat(client.calls).doesNotContain("presence")
        assertThat(manager.state.value).isEqualTo(SessionState.Starting("10001"))

        client.reconnectResults += LoginResult(resumed = false)
        client.simulateDisconnect(DisconnectReason.Network(null))
        runCurrent()

        assertThat(client.calls).contains("presence")
        assertThat(manager.state.value).isInstanceOf(SessionState.Ready::class.java)
    }

    /** 流恢复的重连：不重新执行登录后步骤（当前没有 runOnResume 的步骤） */
    @Test
    fun resumedReconnect_skipsSteps() = runTest {
        val manager = newSessionManager()
        manager.login(credential)
        runCurrent()
        val callsBefore = client.calls.size

        client.simulateDisconnect(DisconnectReason.Network(null))
        runCurrent()

        assertThat(client.calls.drop(callsBefore)).containsExactly("reconnect")
        assertThat(manager.state.value).isInstanceOf(SessionState.Ready::class.java)
    }

    /** 被挤下线：会话结束，清除保存的密码（防止本机重启后自动登录把另一台设备挤下线） */
    @Test
    fun conflict_endsSessionClearingPassword() = runTest {
        val manager = newSessionManager()
        manager.login(credential)
        runCurrent()

        client.simulateDisconnect(DisconnectReason.Conflict)
        runCurrent()

        client.simulateDisconnect(DisconnectReason.Conflict)
        runCurrent()

        assertThat(manager.state.value).isEqualTo(SessionState.Ended("10001", DisconnectReason.Conflict))
        assertThat(credentialStore.load()).isEqualTo(SavedLogin("10001", null))
    }

    /** 账号被禁用：会话结束，清除保存的密码（协议规范 §2.4） */
    @Test
    fun accountDisabled_endsSessionClearingPassword() = runTest {
        val manager = newSessionManager()
        manager.login(credential)
        runCurrent()

        client.simulateDisconnect(DisconnectReason.AccountDisabled)
        runCurrent()

        assertThat(manager.state.value).isEqualTo(SessionState.Ended("10001", DisconnectReason.AccountDisabled))
        assertThat(credentialStore.load()).isEqualTo(SavedLogin("10001", null))
    }

    @Test
    fun autoLogin_withSavedCredential_starts() = runTest {
        val manager = newSessionManager()
        credentialStore.save(credential)

        val started = manager.startAutoLogin()
        runCurrent()

        assertThat(started).isTrue()
        assertThat(client.calls.first()).isEqualTo("login:10001")
        assertThat(manager.state.value).isInstanceOf(SessionState.Ready::class.java)
        assertThat(loggedInUsers).containsExactly("10001")
    }

    @Test
    fun autoLogin_withoutCredential_returnsFalse() = runTest {
        val manager = newSessionManager()

        assertThat(manager.startAutoLogin()).isFalse()
        assertThat(manager.state.value).isEqualTo(SessionState.LoggedOut)
    }

    @Test
    fun logout_clearsPasswordAndDisconnects() = runTest {
        val manager = newSessionManager()
        manager.login(credential)
        runCurrent()

        manager.logout()
        runCurrent()

        assertThat(client.calls.last()).isEqualTo("logout")
        assertThat(manager.state.value).isEqualTo(SessionState.LoggedOut)
        assertThat(credentialStore.load()).isEqualTo(SavedLogin("10001", null))
        // 退出登录时关闭用户数据库
        assertThat(loggedOutCount).isEqualTo(1)
    }
}
