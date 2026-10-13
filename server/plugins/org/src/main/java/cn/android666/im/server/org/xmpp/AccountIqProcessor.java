package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgConstants;
import cn.android666.im.server.org.OrgService;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

/**
 * 账号状态查询（《三端协议规范.md》§3.1）：
 * <pre>
 * &lt;iq type='get'&gt;&lt;account xmlns='urn:android666:im:account:1'/&gt;&lt;/iq&gt;
 * → &lt;account xmlns='urn:android666:im:account:1' mustChangePassword='true'/&gt;
 * </pre>
 */
public class AccountIqProcessor {

    /** 组织架构服务（保存强制改密标记） */
    private final OrgService orgService;

    /**
     * 创建处理器。
     *
     * @param orgService 组织架构服务
     */
    public AccountIqProcessor(OrgService orgService) {
        this.orgService = orgService;
    }

    /**
     * 处理请求。
     *
     * @param request 请求（from 为已认证的本地用户）
     * @return 应答
     */
    public IQ process(IQ request) {
        if (request.getType() != IQ.Type.get || request.getFrom() == null || request.getFrom().getNode() == null) {
            return OrgXml.errorReply(request, PacketError.Condition.bad_request, null);
        }
        IQ reply = IQ.createResultIQ(request);
        reply.setChildElement("account", OrgConstants.NS_ACCOUNT)
                .addAttribute("mustChangePassword", Boolean.toString(orgService.mustChangePassword(request.getFrom().getNode())));
        return reply;
    }
}
