package cn.android666.im.server.org;

import cn.android666.im.server.org.db.OrgDao;
import cn.android666.im.server.org.importer.ImportDirectoryWatcher;
import cn.android666.im.server.org.importer.OpenfireAccountGateway;
import cn.android666.im.server.org.importer.OrgImporter;
import cn.android666.im.server.org.xmpp.AccountEventListener;
import cn.android666.im.server.org.xmpp.AccountInterceptor;
import cn.android666.im.server.org.xmpp.AccountIqProcessor;
import cn.android666.im.server.org.xmpp.AccountManager;
import cn.android666.im.server.org.xmpp.AvatarHashListener;
import cn.android666.im.server.org.xmpp.FunctionIqHandler;
import cn.android666.im.server.org.xmpp.OpenfirePresenceSource;
import cn.android666.im.server.org.xmpp.OrgIqProcessor;
import cn.android666.im.server.org.xmpp.PresenceQueryProcessor;
import cn.android666.im.server.org.xmpp.SubscriptionAutoApprover;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.IQRouter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.disco.IQDiscoInfoHandler;
import org.jivesoftware.openfire.event.UserEventDispatcher;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.vcard.VCardEventDispatcher;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.TaskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.TimerTask;

/**
 * 组织架构插件（org）入口：创建各组件并注册到 Openfire，卸载时全部注销。
 *
 * 提供（《三端协议规范.md》）：
 * <ul>
 *   <li>§3 账号状态查询、强制改密限制、密码强度校验；</li>
 *   <li>§4 组织架构：部门下级、批量员工详情、同步、搜索；</li>
 *   <li>§5 订阅自动同意、在线状态查询；</li>
 *   <li>§4.1 头像哈希：vCard 中的头像变化时更新员工的 avatarHash；</li>
 *   <li>管理后台：Excel 导入、部门 / 员工维护（含禁用踢下线）；导入目录 {@code {Openfire 目录}/org-import/}。</li>
 * </ul>
 *
 * 系统属性：
 * <ul>
 *   <li>{@code org.import.mustChangePassword}：导入的新账号是否首次登录强制改密，默认 true（开发环境为 false）。</li>
 * </ul>
 */
public class OrgPlugin implements Plugin {

    private static final Logger Log = LoggerFactory.getLogger(OrgPlugin.class);

    /** 系统属性：导入的新账号是否强制改密 */
    public static final String PROP_IMPORT_MUST_CHANGE_PASSWORD = "org.import.mustChangePassword";

    /** 当前运行的插件实例，供管理后台的 Servlet 使用（插件卸载后为 null） */
    private static volatile OrgPlugin instance;

    /** 组织架构服务 */
    private OrgService orgService;

    /** 导入器 */
    private OrgImporter importer;

    /** 账号禁用 / 启用 */
    private AccountManager accountManager;

    /** 注册的 IQ 处理器 */
    private List<IQHandler> iqHandlers = List.of();

    /** 注册的报文拦截器 */
    private List<PacketInterceptor> interceptors = List.of();

    /** 用户事件监听 */
    private AccountEventListener accountEventListener;

    /** vCard 事件监听（头像哈希） */
    private AvatarHashListener avatarHashListener;

    /** 定时任务：清理变更日志、检查导入目录 */
    private List<TimerTask> tasks = List.of();

    /**
     * 当前运行的插件实例。
     *
     * @return 插件实例
     * @throws IllegalStateException 插件未运行
     */
    public static OrgPlugin instance() {
        OrgPlugin current = instance;
        if (current == null) {
            throw new IllegalStateException("组织架构插件未运行");
        }
        return current;
    }

    /**
     * 插件加载：读取组织架构，注册 IQ 处理器、disco feature、拦截器、事件监听和定时任务。
     *
     * @param manager         插件管理器
     * @param pluginDirectory 插件解压目录
     */
    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        XMPPServer server = XMPPServer.getInstance();
        String domain = server.getServerInfo().getXMPPDomain();
        orgService = new OrgService(new OrgDao(DbConnectionManager::getConnection), System::currentTimeMillis);
        try {
            orgService.load();
        } catch (SQLException e) {
            // 读不到组织架构时插件无法工作，抛出异常让 Openfire 显示插件加载失败
            throw new IllegalStateException("读取组织架构失败", e);
        }
        importer = new OrgImporter(orgService, new OpenfireAccountGateway());
        accountManager = new AccountManager(orgService);

        // IQ 处理器（§3.1、§4、§5.3）
        AccountIqProcessor account = new AccountIqProcessor(orgService);
        OrgIqProcessor org = new OrgIqProcessor(orgService, domain);
        PresenceQueryProcessor presence = new PresenceQueryProcessor(new OpenfirePresenceSource(), domain);
        iqHandlers = List.of(
                new FunctionIqHandler("IM 账号状态", "account", OrgConstants.NS_ACCOUNT, account::process),
                new FunctionIqHandler("IM 组织架构", "sync", OrgConstants.NS_ORG, org::process),
                new FunctionIqHandler("IM 在线状态查询", "presence-query", OrgConstants.NS_PRESENCE, presence::process));
        IQRouter router = server.getIQRouter();
        iqHandlers.forEach(router::addHandler);

        // 能力发现（§1.7）
        IQDiscoInfoHandler disco = server.getIQDiscoInfoHandler();
        disco.addServerFeature(OrgConstants.NS_ACCOUNT);
        disco.addServerFeature(OrgConstants.NS_ORG);
        disco.addServerFeature(OrgConstants.NS_PRESENCE);

        // 拦截器与事件
        interceptors = List.of(new AccountInterceptor(orgService), new SubscriptionAutoApprover());
        interceptors.forEach(InterceptorManager.getInstance()::addInterceptor);
        accountEventListener = new AccountEventListener(orgService);
        UserEventDispatcher.addListener(accountEventListener);
        avatarHashListener = new AvatarHashListener(orgService);
        VCardEventDispatcher.addListener(avatarHashListener);

        // 定时任务
        TimerTask prune = new TimerTask() {
            @Override
            public void run() {
                try {
                    orgService.pruneChanges();
                } catch (SQLException e) {
                    Log.error("清理组织架构变更日志失败", e);
                }
            }
        };
        Path importDir = JiveGlobals.getHomePath().resolve("org-import");
        ImportDirectoryWatcher watcher = new ImportDirectoryWatcher(importDir, importer, OrgPlugin::importMustChangePassword);
        tasks = List.of(prune, watcher);
        TaskEngine.getInstance().schedule(prune, Duration.ofMinutes(1), Duration.ofHours(24));
        TaskEngine.getInstance().schedule(watcher, Duration.ofSeconds(1), Duration.ofSeconds(10));

        instance = this;
        Log.info("组织架构插件已加载：域名 {}，部门 {} 个，员工 {} 人，版本 {}，导入目录 {}", domain,
                orgService.departmentCount(), orgService.employeeCount(), orgService.version(), importDir);
    }

    /** 插件卸载：注销全部组件 */
    @Override
    public void destroyPlugin() {
        instance = null;
        tasks.forEach(TaskEngine.getInstance()::cancelScheduledTask);
        if (accountEventListener != null) {
            UserEventDispatcher.removeListener(accountEventListener);
        }
        if (avatarHashListener != null) {
            VCardEventDispatcher.removeListener(avatarHashListener);
        }
        interceptors.forEach(InterceptorManager.getInstance()::removeInterceptor);
        XMPPServer server = XMPPServer.getInstance();
        IQDiscoInfoHandler disco = server.getIQDiscoInfoHandler();
        disco.removeServerFeature(OrgConstants.NS_ACCOUNT);
        disco.removeServerFeature(OrgConstants.NS_ORG);
        disco.removeServerFeature(OrgConstants.NS_PRESENCE);
        IQRouter router = server.getIQRouter();
        iqHandlers.forEach(router::removeHandler);
        Log.info("组织架构插件已卸载");
    }

    /**
     * 导入的新账号是否首次登录强制改密（系统属性 {@link #PROP_IMPORT_MUST_CHANGE_PASSWORD}，默认 true）。
     *
     * @return 是否强制改密
     */
    public static boolean importMustChangePassword() {
        return JiveGlobals.getBooleanProperty(PROP_IMPORT_MUST_CHANGE_PASSWORD, true);
    }

    /**
     * 组织架构服务。
     *
     * @return 服务
     */
    public OrgService orgService() {
        return orgService;
    }

    /**
     * 导入器。
     *
     * @return 导入器
     */
    public OrgImporter importer() {
        return importer;
    }

    /**
     * 账号禁用 / 启用。
     *
     * @return 账号管理
     */
    public AccountManager accountManager() {
        return accountManager;
    }
}
