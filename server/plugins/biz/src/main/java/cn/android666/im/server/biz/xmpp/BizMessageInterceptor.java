package cn.android666.im.server.biz.xmpp;

import cn.android666.im.server.biz.BizConstants;
import cn.android666.im.server.biz.BizService;
import cn.android666.im.server.biz.RetractRules;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

import java.sql.SQLException;

/**
 * 客户端发出的单聊 / 群聊消息的服务器端检查（开发计划 4.20、4.21）：
 * <ul>
 *   <li>撤回（XEP-0424）：普通用户只能撤回自己 2 分钟内发送的消息，否则拒绝：{@code not-allowed + retract-timeout}
 *       / {@code forbidden + retract-forbidden}（协议规范 §8.4）；</li>
 *   <li>敏感词：body 命中时拒绝投递：{@code policy-violation + sensitive-word}（协议规范 §6.4）；</li>
 *   <li>通过检查的用户可见消息记录发送方和服务器时间，供撤回校验。</li>
 * </ul>
 * 只处理客户端会话发来、尚未处理的报文；拒绝时 Openfire 用 {@link PacketRejectedException#setRejectionError} 中的错误应答发送方，
 * 错误报文的 id 与原报文相同。数据库出错时放行（只记录日志），不影响正常聊天。
 */
public class BizMessageInterceptor implements PacketInterceptor {

    private static final Logger Log = LoggerFactory.getLogger(BizMessageInterceptor.class);

    /** 业务逻辑 */
    private final BizService service;

    /**
     * 创建拦截器。
     *
     * @param service 业务逻辑
     */
    public BizMessageInterceptor(BizService service) {
        this.service = service;
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
        if (!incoming || processed || !(session instanceof ClientSession) || !(packet instanceof Message message)) {
            return;
        }
        check(message);
    }

    /**
     * 检查一条客户端发出的消息。
     *
     * @param message 消息
     * @throws PacketRejectedException 拒绝
     */
    void check(Message message) throws PacketRejectedException {
        if (message.getType() != Message.Type.chat && message.getType() != Message.Type.groupchat) {
            return;
        }
        JID from = message.getFrom();
        JID to = message.getTo();
        if (from == null || to == null) {
            return;
        }
        String sender = from.toBareJID();
        String receiver = to.toBareJID();
        Element retract = message.getElement().element(QName.get("retract", BizConstants.NS_RETRACT));
        if (retract != null) {
            // 撤回报文的 body 是降级文本"xx 撤回了一条消息"，不检查敏感词，也不记录
            checkRetract(retract.attributeValue("id"), receiver, sender);
            return;
        }
        String body = message.getBody();
        if (body == null) {
            return;
        }
        String word = service.findSensitiveWord(body);
        if (word != null) {
            Log.info("拒绝 {} 发给 {} 的消息：包含敏感词「{}」", sender, receiver, word);
            throw rejected("policy-violation", "modify", BizConstants.ERROR_SENSITIVE_WORD);
        }
        String msgId = messageId(message);
        if (msgId != null) {
            try {
                service.recordMessage(msgId, receiver, sender);
            } catch (SQLException e) {
                Log.error("记录消息失败（不影响投递）：{}", msgId, e);
            }
        }
    }

    /**
     * 撤回校验。
     *
     * @param msgId     被撤回的消息 ID
     * @param receiver  接收方 bare JID
     * @param retractor 撤回者 bare JID
     * @throws PacketRejectedException 不允许撤回
     */
    private void checkRetract(String msgId, String receiver, String retractor) throws PacketRejectedException {
        if (msgId == null || msgId.isEmpty()) {
            return;
        }
        RetractRules.Decision decision;
        try {
            decision = service.checkRetract(msgId, receiver, retractor);
        } catch (SQLException e) {
            Log.error("撤回校验失败，放行：{}", msgId, e);
            return;
        }
        switch (decision) {
            case ALLOW -> Log.info("{} 撤回消息 {}", retractor, msgId);
            case TIMEOUT -> {
                Log.info("拒绝 {} 撤回消息 {}：超过时限", retractor, msgId);
                throw rejected("not-allowed", "cancel", BizConstants.ERROR_RETRACT_TIMEOUT);
            }
            case FORBIDDEN -> {
                Log.info("拒绝 {} 撤回消息 {}：不是自己发送的", retractor, msgId);
                throw rejected("forbidden", "auth", BizConstants.ERROR_RETRACT_FORBIDDEN);
            }
        }
    }

    /**
     * 消息 ID：优先 origin-id（XEP-0359），没有时用报文 id（协议规范 §6.1 两者相同）。
     *
     * @param message 消息
     * @return 消息 ID；都没有时为 null
     */
    static String messageId(Message message) {
        Element origin = message.getElement().element(QName.get("origin-id", BizConstants.NS_SID));
        String id = origin != null ? origin.attributeValue("id") : null;
        if (id == null || id.isEmpty()) {
            id = message.getID();
        }
        return id == null || id.isEmpty() ? null : id;
    }

    /**
     * 创建带错误应答的拒绝异常。直接构造 error 元素：Tinder 的 {@link PacketError.Condition} 没有 RFC 6120 新增的
     * {@code policy-violation}（协议规范 §6.4 要求使用它）。
     *
     * @param condition 标准错误条件的元素名（如 policy-violation）
     * @param type      错误类型（modify / cancel / auth）
     * @param appCode   自定义错误码
     * @return 异常
     */
    static PacketRejectedException rejected(String condition, String type, String appCode) {
        Element error = DocumentHelper.createElement("error");
        error.addAttribute("type", type);
        error.addElement(QName.get(condition, NS_STANZAS));
        error.addElement(QName.get("app-error", BizConstants.NS_ERROR)).addAttribute("code", appCode);
        PacketRejectedException e = new PacketRejectedException(appCode);
        e.setRejectionError(new PacketError(error));
        return e;
    }

    /** 标准 stanza 错误条件的命名空间 */
    private static final String NS_STANZAS = "urn:ietf:params:xml:ns:xmpp-stanzas";
}
