package cn.android666.im.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.security.CredentialCipher
import cn.android666.im.core.xmpp.connection.ReconnectPolicy
import cn.android666.im.core.xmpp.connection.XmppConnectionManager
import cn.android666.im.core.xmpp.extension.account.AccountStatusRequest
import cn.android666.im.data.account.CredentialStore
import cn.android666.im.data.session.SessionManager
import cn.android666.im.data.session.steps.AccountStatusStep
import cn.android666.im.data.session.steps.CapabilitiesStep
import cn.android666.im.data.session.steps.EnableCarbonsStep
import cn.android666.im.data.session.steps.InitialPresenceStep
import cn.android666.im.data.session.steps.ServerTimeStep
import cn.android666.im.data.settings.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 测试用的会话环境：真实的 [SessionManager]、[XmppConnectionManager]、[CredentialStore]、[AppSettingsStore]
 * （DataStore 写临时文件），XMPP 用 [FakeXmppClient]。ViewModel 测试用它代替整套依赖。
 *
 * @param scope 运行会话协程和 DataStore 的作用域（一般为 runTest 的 backgroundScope）
 * @param dir 存放 DataStore 文件的临时目录
 */
class SessionTestRig(scope: CoroutineScope, dir: File) {

    /** 假的 XMPP 客户端 */
    val client = FakeXmppClient()

    /** 网络状态 */
    val network = FakeNetworkMonitor()

    /** 账号状态 IQ 应答中的 mustChangePassword */
    var mustChangePassword = false

    /** 可逆的假加密 */
    private val cipher = object : CredentialCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }

    /** 凭证存储 */
    val credentialStore = CredentialStore(dataStore, cipher)

    /** 设备级设置 */
    val settings = AppSettingsStore(dataStore)

    /** 连接管理 */
    val connectionManager = XmppConnectionManager(client, network, scope) { ReconnectPolicy(jitterRatio = 0.0) }

    /** 登录编排 */
    val sessionManager = SessionManager(
        client = client,
        connectionManager = connectionManager,
        credentialStore = credentialStore,
        onUserLoggedIn = {},
        steps = listOf(
            EnableCarbonsStep(client),
            ServerTimeStep(client, ServerClock(FakeClock())),
            CapabilitiesStep(client),
            AccountStatusStep(client),
            InitialPresenceStep(client),
        ),
        scope = scope,
    )

    init {
        client.iqResponder = { request ->
            if (request is AccountStatusRequest) {
                "<account xmlns='urn:android666:im:account:1' mustChangePassword='$mustChangePassword'/>"
            } else {
                null
            }
        }
    }
}
