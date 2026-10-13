package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgConstants;
import cn.android666.im.server.org.OrgService;
import org.dom4j.QName;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.lockout.LockOutManager;
import org.jivesoftware.openfire.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.StreamError;

import java.sql.SQLException;

/**
 * 账号禁用 / 启用（开发计划 3.9，《三端协议规范.md》§2.4）：
 * 禁用时在组织架构中标记（客户端同步时视为删除）、锁定账号（无法再登录），
 * 并以 {@code policy-violation} + {@code <account-disabled xmlns='urn:android666:im:error:1'/>} 关闭该用户的所有连接。
 */
public class AccountManager {

    private static final Logger Log = LoggerFactory.getLogger(AccountManager.class);

    /** 组织架构服务 */
    private final OrgService orgService;

    /**
     * 创建账号管理。
     *
     * @param orgService 组织架构服务
     */
    public AccountManager(OrgService orgService) {
        this.orgService = orgService;
    }

    /**
     * 禁用账号并踢下线。
     *
     * @param username 工号
     * @throws SQLException 数据库错误
     */
    public void disable(String username) throws SQLException {
        orgService.setDisabled(username, true);
        LockOutManager.getInstance().disableAccount(username, null, null);
        int closed = 0;
        // 用 getSessions(JID)：包括还没有发送初始 presence 的会话（getSessions(String) 只返回已上线的，见 Openfire OF-3132）
        JID bare = XMPPServer.getInstance().createJID(username, null);
        for (ClientSession session : SessionManager.getInstance().getSessions(bare)) {
            StreamError error = new StreamError(StreamError.Condition.policy_violation);
            error.getElement().addElement(QName.get(OrgConstants.STREAM_ERROR_ACCOUNT_DISABLED, OrgConstants.NS_ERROR));
            session.close(error);
            closed++;
        }
        Log.info("禁用账号 {}，关闭连接 {} 个", username, closed);
    }

    /**
     * 启用账号。
     *
     * @param username 工号
     * @throws SQLException 数据库错误
     */
    public void enable(String username) throws SQLException {
        orgService.setDisabled(username, false);
        LockOutManager.getInstance().enableAccount(username);
        Log.info("启用账号 {}", username);
    }
}
