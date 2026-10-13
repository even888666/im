package cn.android666.im.di

import android.content.Context
import cn.android666.im.BuildConfig
import cn.android666.im.core.datastore.settingsDataStore
import cn.android666.im.core.network.AndroidNetworkMonitor
import cn.android666.im.core.network.NetworkMonitor
import cn.android666.im.core.security.AesGcmCredentialCipher
import cn.android666.im.core.security.AndroidKeyStoreKeyProvider
import cn.android666.im.core.security.RootCaTrust
import cn.android666.im.data.account.CredentialStore
import cn.android666.im.data.session.SessionManager
import cn.android666.im.data.session.steps.AccountStatusStep
import cn.android666.im.data.session.steps.CapabilitiesStep
import cn.android666.im.data.session.steps.EnableCarbonsStep
import cn.android666.im.data.session.steps.InitialPresenceStep
import cn.android666.im.data.session.steps.ServerTimeStep
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.connection.XmppConnectionManager
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import org.jivesoftware.smack.android.AndroidSmackInitializer
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.DefaultDispatcherProvider
import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.common.UuidIdGenerator
import cn.android666.im.core.log.LogFilePrefixManager
import cn.android666.im.core.log.LogFilePrefixStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级依赖容器，在 [cn.android666.im.App.onCreate] 中创建，持有所有单例（见 Android工程架构.md §4.1）。
 *
 * 项目采用手动依赖注入（不用 Hilt，见 Android工程架构.md §11）：所有类的依赖都从构造函数传入，
 * 由本容器负责组装；测试时直接构造被测类并传入假实现，不需要本容器。
 *
 * 新增单例时：无需立即创建的用 `by lazy`，避免拖慢冷启动。
 *
 * @param context 任意 Context，内部只保存 applicationContext，避免泄漏 Activity
 */
class AppContainer(context: Context) {

    /** 应用级 Context */
    val appContext: Context = context.applicationContext

    /** 协程调度器 */
    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()

    /**
     * 应用级协程作用域，用于生命周期与进程一致的长期任务（连接管理、同步等）。
     * 使用 SupervisorJob：某个子任务失败不会取消其他任务。禁止使用 GlobalScope（Android工程架构.md §10）。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** 本地时钟 */
    val clock: Clock = SystemTimeClock

    /** 服务器时间（登录后用 XEP-0202 校准） */
    val serverClock: ServerClock = ServerClock(clock)

    /** 协议 ID 生成器 */
    val idGenerator: IdGenerator = UuidIdGenerator()

    /** 只信任企业根证书的 TLS 配置，XMPP 与 HTTP 共用 */
    val rootCaTrust: RootCaTrust by lazy { RootCaTrust.fromResources(appContext) }

    /** XMPP 服务器地址 */
    val serverConfig: ServerConfig = ServerConfig(
        domain = BuildConfig.IM_DOMAIN,
        host = BuildConfig.IM_HOST.ifBlank { null },
    )

    /**
     * XMPP 客户端。Debug 版记录报文日志，Release 版默认关闭（开发计划 8.10 提供运行时开关）。
     * 在 Android 上使用 Smack 前必须先完成其 Android 初始化（DNS 解析等）。
     */
    val xmppClient: XmppClient by lazy {
        AndroidSmackInitializer.initialize(appContext)
        SmackXmppClient(
            serverConfig = serverConfig,
            trustManager = rootCaTrust.trustManager,
            dispatchers = dispatchers,
            clock = clock,
            logStanzas = { BuildConfig.DEBUG },
        )
    }

    /** 登录凭证存储：工号明文、密码用 Android Keystore 中的密钥加密 */
    val credentialStore: CredentialStore by lazy {
        CredentialStore(appContext.settingsDataStore, AesGcmCredentialCipher(AndroidKeyStoreKeyProvider()))
    }

    /** 网络状态监听 */
    val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext) }

    /** 连接管理：断线重连、心跳检测 */
    val connectionManager: XmppConnectionManager by lazy {
        XmppConnectionManager(xmppClient, networkMonitor, applicationScope)
    }

    /**
     * 登录编排：登录流程的唯一入口。登录后步骤按协议规范 §2.2 的顺序注册，
     * 后续模块（Roster、组织架构同步、群列表、MAM 补拉等）在对应位置加入。
     */
    val sessionManager: SessionManager by lazy {
        SessionManager(
            client = xmppClient,
            connectionManager = connectionManager,
            credentialStore = credentialStore,
            onUserLoggedIn = logFilePrefixManager::onLogin,
            steps = listOf(
                EnableCarbonsStep(xmppClient),
                ServerTimeStep(xmppClient, serverClock),
                CapabilitiesStep(xmppClient),
                AccountStatusStep(xmppClient),
                InitialPresenceStep(xmppClient),
            ),
            scope = applicationScope,
        )
    }

    /** 日志文件名前缀管理：登录成功后切换为工号 */
    val logFilePrefixManager: LogFilePrefixManager by lazy {
        LogFilePrefixManager(LogFilePrefixStore.create(appContext))
    }
}
