package cn.android666.im.server.org.xmpp;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.UserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.xmpp.packet.Presence;

/**
 * 订阅请求自动同意（《三端协议规范.md》§5.1）：企业内部添加常用联系人不需要对方确认。
 *
 * 本地用户发出的 {@code subscribe} 被服务器处理之后（对方的花名册中已有"待同意"的条目），
 * 以对方的名义发送 {@code subscribed}，对方离线时也立即生效。
 */
public class SubscriptionAutoApprover implements PacketInterceptor {

    private static final Logger Log = LoggerFactory.getLogger(SubscriptionAutoApprover.class);

    /**
     * 拦截报文。
     *
     * @param packet    报文
     * @param session   报文所属的会话
     * @param incoming  是否为服务器收到的报文
     * @param processed 是否已处理
     */
    @Override
    public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) {
        if (!incoming || !processed || !(session instanceof ClientSession) || !(packet instanceof Presence presence)) {
            return;
        }
        if (presence.getType() != Presence.Type.subscribe || presence.getFrom() == null || presence.getTo() == null) {
            return;
        }
        XMPPServer server = XMPPServer.getInstance();
        JID contact = presence.getTo().asBareJID();
        JID requester = presence.getFrom().asBareJID();
        // 只处理本域的已注册用户之间的订阅，不能订阅自己
        if (!server.isLocal(contact) || contact.getNode() == null || contact.equals(requester)
                || !UserManager.getInstance().isRegisteredUser(contact, false)) {
            return;
        }
        Presence subscribed = new Presence(Presence.Type.subscribed);
        subscribed.setFrom(contact);
        subscribed.setTo(requester);
        server.getPresenceRouter().route(subscribed);
        Log.debug("自动同意订阅：{} → {}", requester, contact);
    }
}
