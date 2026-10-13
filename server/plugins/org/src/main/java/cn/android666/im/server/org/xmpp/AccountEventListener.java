package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgService;
import org.jivesoftware.openfire.event.UserEventListener;
import org.jivesoftware.openfire.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * 监听 Openfire 的用户事件：
 * <ul>
 *   <li>密码被修改（XEP-0077 改密或管理员重置）→ 清除强制改密标记（《三端协议规范.md》§3.2）；</li>
 *   <li>用户被删除（例如在 Openfire 自带的用户管理中删除）→ 组织架构中标记为已禁用，客户端同步时视为删除。</li>
 * </ul>
 */
public class AccountEventListener implements UserEventListener {

    private static final Logger Log = LoggerFactory.getLogger(AccountEventListener.class);

    /** Openfire 在 user_modified 事件参数中表示"密码被修改"的值 */
    static final String PASSWORD_MODIFIED = "passwordModified";

    /** 组织架构服务 */
    private final OrgService orgService;

    /**
     * 创建监听器。
     *
     * @param orgService 组织架构服务
     */
    public AccountEventListener(OrgService orgService) {
        this.orgService = orgService;
    }

    /**
     * 用户被创建：不处理（员工信息由导入或后台维护写入）。
     *
     * @param user   用户
     * @param params 事件参数
     */
    @Override
    public void userCreated(User user, Map<String, Object> params) {
        // 不处理
    }

    /**
     * 用户即将被删除：组织架构中标记为已禁用。
     *
     * @param user   用户
     * @param params 事件参数
     */
    @Override
    public void userDeleting(User user, Map<String, Object> params) {
        try {
            if (orgService.setDisabled(user.getUsername(), true)) {
                Log.info("用户 {} 被删除，组织架构中标记为已禁用", user.getUsername());
            }
        } catch (SQLException e) {
            Log.error("标记已删除用户 {} 为禁用失败", user.getUsername(), e);
        }
    }

    /**
     * 用户被修改：密码被修改时清除强制改密标记。
     *
     * @param user   用户
     * @param params 事件参数（type = passwordModified 表示改密）
     */
    @Override
    public void userModified(User user, Map<String, Object> params) {
        if (params == null || !PASSWORD_MODIFIED.equals(params.get("type"))) {
            return;
        }
        try {
            if (orgService.setMustChangePassword(user.getUsername(), false)) {
                Log.info("用户 {} 已修改密码，清除强制改密标记", user.getUsername());
            }
        } catch (SQLException e) {
            Log.error("清除用户 {} 的强制改密标记失败", user.getUsername(), e);
        }
    }
}
