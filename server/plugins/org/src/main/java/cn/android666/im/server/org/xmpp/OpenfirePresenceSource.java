package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgConstants;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.PresenceManager;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 Openfire 的会话和最后活跃记录读取在线状态（{@link PresenceSource} 的生产实现）。
 */
public class OpenfirePresenceSource implements PresenceSource {

    /**
     * 用户各在线端的状态：遍历该用户的客户端会话，取每个会话最近一次发出的 presence。
     *
     * @param username 工号
     * @return 在线端列表；离线时为空列表
     */
    @Override
    public List<ResourceState> onlineResources(String username) {
        List<ResourceState> result = new ArrayList<>();
        for (ClientSession session : SessionManager.getInstance().getSessions(username)) {
            Presence presence = session.getPresence();
            if (presence == null || !presence.isAvailable()) {
                continue;
            }
            Element state = presence.getElement().element(QName.get("state", OrgConstants.NS_PRESENCE));
            result.add(new ResourceState(
                    session.getAddress().getResource(),
                    presence.getShow() == null ? null : presence.getShow().name(),
                    presence.getStatus(),
                    state == null ? null : state.attributeValue("code")));
        }
        return result;
    }

    /**
     * 用户最后一次下线的时间。Openfire 记录的是"距离最后一次下线过了多少毫秒"，换算为时间点。
     *
     * @param username 工号
     * @return 时间（毫秒）；从未登录或用户不存在时为 null
     */
    @Override
    public Long lastActiveMillis(String username) {
        try {
            User user = UserManager.getInstance().getUser(username);
            PresenceManager presenceManager = XMPPServer.getInstance().getPresenceManager();
            long idle = presenceManager.getLastActivity(user);
            return idle < 0 ? null : System.currentTimeMillis() - idle;
        } catch (UserNotFoundException e) {
            return null;
        }
    }
}
