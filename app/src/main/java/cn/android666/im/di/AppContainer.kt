package cn.android666.im.di

import android.content.Context
import cn.android666.im.BuildConfig
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.datastore.settingsDataStore
import cn.android666.im.core.network.AndroidNetworkMonitor
import cn.android666.im.core.network.NetworkMonitor
import cn.android666.im.core.security.AesGcmCredentialCipher
import cn.android666.im.core.security.AndroidKeyStoreKeyProvider
import cn.android666.im.core.security.RootCaTrust
import cn.android666.im.data.account.CredentialStore
import cn.android666.im.data.avatar.AvatarRepository
import cn.android666.im.data.contact.ContactRepository
import cn.android666.im.data.contact.RosterStep
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.data.message.IncomingMessageHandler
import cn.android666.im.data.message.MessageRepository
import cn.android666.im.data.message.ReadMarkers
import cn.android666.im.data.message.SendQueue
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.data.presence.PresenceRepository
import cn.android666.im.data.org.OrgSyncStep
import cn.android666.im.data.settings.AppSettingsStore
import cn.android666.im.data.session.AppVisibilityHandler
import cn.android666.im.data.session.SessionManager
import cn.android666.im.data.session.steps.AccountStatusStep
import cn.android666.im.data.session.steps.CapabilitiesStep
import cn.android666.im.data.session.steps.EnableCarbonsStep
import cn.android666.im.data.session.steps.InitialPresenceStep
import cn.android666.im.data.session.steps.ServerTimeStep
import cn.android666.im.service.ImService
import cn.android666.im.service.KeepAliveChecker
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
import java.io.File

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

    /** 设备级设置（隐私政策同意状态等） */
    val appSettingsStore: AppSettingsStore by lazy { AppSettingsStore(appContext.settingsDataStore) }

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
            onUserLoggedIn = { username ->
                logFilePrefixManager.onLogin(username)
                userDatabaseHolder.open(username)
                // 确保发送队列已创建：它监听用户数据库的打开，恢复上次未发完的消息
                sendQueue.wake()
                // 确保已订阅收到的消息：发送初始 presence 后服务器立即投递离线消息
                incomingMessageHandler
            },
            steps = listOf(
                EnableCarbonsStep(xmppClient),
                ServerTimeStep(xmppClient, serverClock),
                CapabilitiesStep(xmppClient),
                AccountStatusStep(xmppClient),
                RosterStep(contactRepository),
                InitialPresenceStep(xmppClient) { presenceRepository.loadMyPresence() },
                OrgSyncStep(orgRepository),
            ),
            scope = applicationScope,
            onUserLoggedOut = {
                presenceRepository.onLogout()
                userDatabaseHolder.close()
            },
        )
    }

    /** 当前账号的用户数据库（按工号分库，登录时打开、退出登录时关闭） */
    val userDatabaseHolder: UserDatabaseHolder by lazy { UserDatabaseHolder(UserDatabaseHolder.create(appContext)) }

    /** 头像：按 avatarHash 缓存 vCard 头像、设置本人头像 */
    val avatarRepository: AvatarRepository by lazy {
        AvatarRepository(xmppClient, File(appContext.filesDir, "avatars"), dispatchers, applicationScope)
    }

    /** 在线状态：本人状态、同事状态查询（缓存 60 秒） */
    val presenceRepository: PresenceRepository by lazy { PresenceRepository(xmppClient, userDatabaseHolder, clock) }

    /** 常用联系人（花名册） */
    val contactRepository: ContactRepository by lazy { ContactRepository(xmppClient, userDatabaseHolder, applicationScope) }

    /** 组织架构：同步、本地查询与搜索 */
    val orgRepository: OrgRepository by lazy { OrgRepository(xmppClient, userDatabaseHolder, clock) }

    /** 发送队列：按顺序发送"发送中"的消息，等待服务器确认 */
    val sendQueue: SendQueue by lazy { SendQueue(xmppClient, userDatabaseHolder, applicationScope, clock) }

    /** 本人 bare JID（当前打开的用户数据库对应的账号）；未登录时为 null */
    val selfJid: () -> String? = { userDatabaseHolder.username?.let { "$it@${serverConfig.domain}" } }

    /** 消息仓库：发送入口、消息与会话写库 */
    val messageRepository: MessageRepository by lazy {
        MessageRepository(userDatabaseHolder, sendQueue, serverClock, idGenerator, selfJid)
    }

    /** 会话列表：查询、删除、未读数 */
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(userDatabaseHolder) }

    /** 单聊已读标记 */
    val readMarkers: ReadMarkers by lazy { ReadMarkers(xmppClient, messageRepository, conversationRepository, idGenerator) }

    /** 收到的消息的分发：去重写库、回执、已读 */
    val incomingMessageHandler: IncomingMessageHandler by lazy {
        IncomingMessageHandler(xmppClient, messageRepository, readMarkers, serverClock, idGenerator, selfJid, applicationScope)
    }

    /** 前后台切换处理（CSI、回到前台立即重连、按需同步组织架构） */
    val appVisibilityHandler: AppVisibilityHandler by lazy {
        AppVisibilityHandler(xmppClient, connectionManager, applicationScope, listOf({ orgRepository.syncIfStale() }))
    }

    /** 保活检查：开机、升级、定期巡检时调用 */
    val keepAliveChecker: KeepAliveChecker by lazy {
        KeepAliveChecker(
            isServiceRunning = { ImService.isRunning },
            hasSavedCredential = { credentialStore.load()?.canAutoLogin == true },
            startService = { reason -> ImService.start(appContext, reason) },
            connectionState = { xmppClient.connectionState.value },
            reconnectNow = connectionManager::reconnectNow,
        )
    }

    /** 日志文件名前缀管理：登录成功后切换为工号 */
    val logFilePrefixManager: LogFilePrefixManager by lazy {
        LogFilePrefixManager(LogFilePrefixStore.create(appContext))
    }
}
