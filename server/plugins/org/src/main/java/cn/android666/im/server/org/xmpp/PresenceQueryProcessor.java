package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgConstants;
import org.dom4j.Element;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 查询任意同事的在线状态（《三端协议规范.md》§5.3），不需要订阅：
 * <pre>
 * &lt;presence-query xmlns='urn:android666:im:presence:1'&gt;&lt;item jid='...'/&gt;&lt;/presence-query&gt;
 * → &lt;item jid='...' online='true'&gt;&lt;resource name='android' show='dnd' status='...' code='meeting'/&gt;&lt;/item&gt;
 *   &lt;item jid='...' online='false' lastActive='2026-10-13T01:20:00.000Z'/&gt;
 * </pre>
 */
public class PresenceQueryProcessor {

    /** XEP-0082 时间格式：UTC，精确到毫秒（§1.4） */
    static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 在线状态来源 */
    private final PresenceSource source;

    /** XMPP 域名 */
    private final String domain;

    /**
     * 创建处理器。
     *
     * @param source 在线状态来源
     * @param domain XMPP 域名
     */
    public PresenceQueryProcessor(PresenceSource source, String domain) {
        this.source = source;
        this.domain = domain;
    }

    /**
     * 处理请求。
     *
     * @param request 请求
     * @return 应答
     */
    public IQ process(IQ request) {
        Element child = request.getChildElement();
        if (request.getType() != IQ.Type.get || child == null || !"presence-query".equals(child.getName())) {
            return OrgXml.errorReply(request, PacketError.Condition.bad_request, null);
        }
        List<Element> items = child.elements("item");
        if (items.size() > OrgConstants.MAX_BATCH) {
            return OrgXml.errorReply(request, PacketError.Condition.bad_request, OrgConstants.ERROR_TOO_MANY_ITEMS);
        }
        IQ reply = IQ.createResultIQ(request);
        Element el = reply.setChildElement("presence-query", OrgConstants.NS_PRESENCE);
        for (Element item : items) {
            String jid = item.attributeValue("jid", "");
            Element out = el.addElement("item").addAttribute("jid", jid);
            String username = usernameOf(jid);
            List<PresenceSource.ResourceState> resources = username == null ? List.of() : source.onlineResources(username);
            out.addAttribute("online", Boolean.toString(!resources.isEmpty()));
            if (resources.isEmpty()) {
                Long lastActive = username == null ? null : source.lastActiveMillis(username);
                if (lastActive != null) {
                    out.addAttribute("lastActive", TIME_FORMAT.format(Instant.ofEpochMilli(lastActive)));
                }
            }
            for (PresenceSource.ResourceState r : resources) {
                Element res = out.addElement("resource").addAttribute("name", r.resource());
                // 属性为 null 时 dom4j 不添加该属性
                res.addAttribute("show", r.show());
                res.addAttribute("status", r.status());
                res.addAttribute("code", r.code());
            }
        }
        return reply;
    }

    /**
     * JID 的用户名部分；不是本域用户时返回 null。
     *
     * @param jid JID 字符串
     * @return 工号或 null
     */
    private String usernameOf(String jid) {
        try {
            JID parsed = new JID(jid);
            return domain.equals(parsed.getDomain()) ? parsed.getNode() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
