package cn.android666.im.server.biz;

import cn.android666.im.server.biz.db.BizDao;
import cn.android666.im.server.biz.xmpp.BizMessageInterceptor;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.TaskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import java.util.TimerTask;

/**
 * 业务扩展插件（biz）入口（开发计划 4.19）：创建各组件并注册到 Openfire，卸载时全部注销。
 *
 * 提供（《三端协议规范.md》）：
 * <ul>
 *   <li>§8.4 撤回校验：普通用户只能撤回自己 2 分钟内的消息；</li>
 *   <li>§6.4 敏感词过滤：命中的单聊 / 群聊消息拒绝投递；管理后台维护词库；</li>
 *   <li>之后加入：§10 群聊扩展（M6）、§14.2 诊断上报（M8），届时在 disco 中声明对应的 feature。</li>
 * </ul>
 *
 * 系统属性：
 * <ul>
 *   <li>{@code biz.retract.limitSeconds}：撤回时限（秒），默认 120。</li>
 * </ul>
 */
public class BizPlugin implements Plugin {

    private static final Logger Log = LoggerFactory.getLogger(BizPlugin.class);

    /** 系统属性：撤回时限（秒） */
    public static final String PROP_RETRACT_LIMIT_SECONDS = "biz.retract.limitSeconds";

    /** 默认撤回时限（秒） */
    public static final int DEFAULT_RETRACT_LIMIT_SECONDS = 120;

    /** 当前运行的插件实例，供管理后台的 Servlet 使用（插件卸载后为 null） */
    private static volatile BizPlugin instance;

    /** 业务逻辑 */
    private BizService service;

    /** 消息拦截器 */
    private BizMessageInterceptor interceptor;

    /** 定时任务：清理过期的消息记录 */
    private TimerTask pruneTask;

    /**
     * 当前运行的插件实例。
     *
     * @return 插件实例
     * @throws IllegalStateException 插件未运行
     */
    public static BizPlugin instance() {
        BizPlugin current = instance;
        if (current == null) {
            throw new IllegalStateException("业务扩展插件未运行");
        }
        return current;
    }

    /**
     * 业务逻辑。
     *
     * @return 服务
     */
    public BizService service() {
        return service;
    }

    /**
     * 插件加载：加载敏感词库，注册消息拦截器和定时清理任务。
     *
     * @param manager         插件管理器
     * @param pluginDirectory 插件解压目录
     */
    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        service = new BizService(
                new BizDao(DbConnectionManager::getConnection),
                System::currentTimeMillis,
                () -> JiveGlobals.getIntProperty(PROP_RETRACT_LIMIT_SECONDS, DEFAULT_RETRACT_LIMIT_SECONDS) * 1000L);
        try {
            service.loadWords();
        } catch (SQLException e) {
            throw new IllegalStateException("读取敏感词库失败", e);
        }
        interceptor = new BizMessageInterceptor(service);
        InterceptorManager.getInstance().addInterceptor(interceptor);

        pruneTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    service.pruneMessages();
                } catch (SQLException e) {
                    Log.error("清理消息记录失败", e);
                }
            }
        };
        TaskEngine.getInstance().schedule(pruneTask, Duration.ofMinutes(5), Duration.ofHours(24));

        instance = this;
        Log.info("业务扩展插件已加载：撤回时限 {} 秒", JiveGlobals.getIntProperty(PROP_RETRACT_LIMIT_SECONDS, DEFAULT_RETRACT_LIMIT_SECONDS));
    }

    /** 插件卸载：注销全部组件 */
    @Override
    public void destroyPlugin() {
        instance = null;
        if (pruneTask != null) {
            TaskEngine.getInstance().cancelScheduledTask(pruneTask);
        }
        if (interceptor != null) {
            InterceptorManager.getInstance().removeInterceptor(interceptor);
        }
        Log.info("业务扩展插件已卸载");
    }
}
