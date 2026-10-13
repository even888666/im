package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgConstants;
import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.PasswordRules;
import org.dom4j.Element;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

/**
 * 账号相关的服务器端限制（《三端协议规范.md》§3）：
 * <ul>
 *   <li>需要强制改密的用户发出的 message 一律拒绝：{@code forbidden} + {@code must-change-password}，防止绕过客户端限制；</li>
 *   <li>XEP-0077 改密时校验密码强度，不满足规则时拒绝：{@code not-acceptable} + {@code weak-password}。</li>
 * </ul>
 * 只处理客户端会话发来、尚未处理的报文；拒绝时 Openfire 用 {@link PacketRejectedException#setRejectionError} 中的错误应答发送方。
 */
public class AccountInterceptor implements PacketInterceptor {

    private static final Logger Log = LoggerFactory.getLogger(AccountInterceptor.class);

    /** XEP-0077 命名空间 */
    static final String NS_REGISTER = "jabber:iq:register";

    /** 组织架构服务（强制改密标记） */
    private final OrgService orgService;

    /**
     * 创建拦截器。
     *
     * @param orgService 组织架构服务
     */
    public AccountInterceptor(OrgService orgService) {
        this.orgService = orgService;
    }

    /**
     * 拦截报文。
     *
     * @param packet    报文
     * @param session   报文所属的会话
     * @param incoming  是否为服务器收到的报文
     * @param processed 是否已处理
     * @throws PacketRejectedException 拒绝该报文
     */
    @Override
    public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed)
            throws PacketRejectedException {
        if (!incoming || processed || !(session instanceof ClientSession) || packet.getFrom() == null) {
            return;
        }
        String username = packet.getFrom().getNode();
        if (username == null) {
            return;
        }
        if (packet instanceof Message message) {
            checkMessage(message, username);
        } else if (packet instanceof IQ iq) {
            checkPasswordChange(iq, username);
        }
    }

    /**
     * 需要强制改密的用户不能发消息。
     *
     * @param message  消息
     * @param username 发送者工号
     * @throws PacketRejectedException 需要改密
     */
    void checkMessage(Message message, String username) throws PacketRejectedException {
        if (message.getType() != Message.Type.error && orgService.mustChangePassword(username)) {
            Log.info("拒绝 {} 发送消息：尚未修改初始密码", username);
            throw rejected(PacketError.Condition.forbidden, OrgConstants.ERROR_MUST_CHANGE_PASSWORD);
        }
    }

    /**
     * 改密时校验密码强度。
     *
     * @param iq       IQ
     * @param username 发送者工号
     * @throws PacketRejectedException 密码不满足规则
     */
    void checkPasswordChange(IQ iq, String username) throws PacketRejectedException {
        Element child = iq.getChildElement();
        if (iq.getType() != IQ.Type.set || child == null || !NS_REGISTER.equals(child.getNamespaceURI())) {
            return;
        }
        Element password = child.element("password");
        if (password != null && !PasswordRules.isAcceptable(password.getText())) {
            Log.info("拒绝 {} 修改密码：新密码不符合规则", username);
            throw rejected(PacketError.Condition.not_acceptable, OrgConstants.ERROR_WEAK_PASSWORD);
        }
    }

    /**
     * 创建带错误应答的拒绝异常。
     *
     * @param condition 标准错误条件
     * @param appCode   自定义错误码
     * @return 异常
     */
    private static PacketRejectedException rejected(PacketError.Condition condition, String appCode) {
        PacketRejectedException e = new PacketRejectedException(appCode);
        e.setRejectionError(OrgXml.error(condition, appCode));
        return e;
    }
}
