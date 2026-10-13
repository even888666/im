package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgConstants;
import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.model.Department;
import cn.android666.im.server.org.model.Employee;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 组织架构查询（《三端协议规范.md》§4，命名空间 {@code urn:android666:im:org:1}）：
 * dept-children（§4.2）、employees（§4.3）、sync（§4.4）、search（§4.5）。
 */
public class OrgIqProcessor {

    private static final Logger Log = LoggerFactory.getLogger(OrgIqProcessor.class);

    /** dept-children 员工分页的默认每页条数 */
    static final int DEFAULT_CHILDREN_PAGE = 200;

    /** sync 的默认每页条数 */
    static final int DEFAULT_SYNC_PAGE = 500;

    /** search 的默认条数 */
    static final int DEFAULT_SEARCH_PAGE = 20;

    /** 组织架构服务 */
    private final OrgService orgService;

    /** XMPP 域名 */
    private final String domain;

    /**
     * 创建处理器。
     *
     * @param orgService 组织架构服务
     * @param domain     XMPP 域名（拼接和校验 JID）
     */
    public OrgIqProcessor(OrgService orgService, String domain) {
        this.orgService = orgService;
        this.domain = domain;
    }

    /**
     * 处理请求，按子元素名称分发。
     *
     * @param request 请求
     * @return 应答
     */
    public IQ process(IQ request) {
        Element child = request.getChildElement();
        if (request.getType() != IQ.Type.get || child == null) {
            return OrgXml.errorReply(request, PacketError.Condition.bad_request, null);
        }
        try {
            return switch (child.getName()) {
                case "dept-children" -> deptChildren(request, child);
                case "employees" -> employees(request, child);
                case "sync" -> sync(request, child);
                case "search" -> search(request, child);
                default -> OrgXml.errorReply(request, PacketError.Condition.feature_not_implemented, null);
            };
        } catch (IllegalArgumentException e) {
            Log.debug("组织架构请求参数错误：{}", e.getMessage());
            return OrgXml.errorReply(request, PacketError.Condition.bad_request, null);
        } catch (SQLException e) {
            Log.error("组织架构请求处理失败", e);
            return OrgXml.errorReply(request, PacketError.Condition.internal_server_error, null);
        }
    }

    /**
     * 部门下级（§4.2）：下级部门全部返回，直属员工按 RSM 分页（游标为员工下标）。
     *
     * @param request 请求
     * @param child   dept-children 元素
     * @return 应答
     */
    private IQ deptChildren(IQ request, Element child) {
        String deptId = attr(child, "deptId");
        OrgXml.RsmRequest rsm = OrgXml.parseRsm(child, DEFAULT_CHILDREN_PAGE);
        int afterIndex = rsm.after() == null ? -1 : parseInt(rsm.after());
        OrgService.Children result = orgService.children(deptId, afterIndex, rsm.max());

        IQ reply = IQ.createResultIQ(request);
        Element el = reply.setChildElement("dept-children", OrgConstants.NS_ORG).addAttribute("deptId", deptId);
        for (Department d : result.departments()) {
            OrgXml.addDepartment(el, d, orgService.memberCount(d.id()), null);
        }
        for (Employee e : result.employees()) {
            OrgXml.addEmployee(el, e, domain, null);
        }
        boolean empty = result.employees().isEmpty();
        OrgXml.addRsmResult(el,
                empty ? null : Integer.toString(result.firstIndex()),
                empty ? null : Integer.toString(result.firstIndex() + result.employees().size() - 1),
                result.total());
        return reply;
    }

    /**
     * 批量员工详情（§4.3）：最多 100 个，不存在或已离职返回 op='delete'。
     *
     * @param request 请求
     * @param child   employees 元素
     * @return 应答
     */
    private IQ employees(IQ request, Element child) {
        List<Element> items = child.elements("item");
        if (items.size() > OrgConstants.MAX_BATCH) {
            return OrgXml.errorReply(request, PacketError.Condition.bad_request, OrgConstants.ERROR_TOO_MANY_ITEMS);
        }
        List<String> jids = new ArrayList<>(items.size());
        List<String> usernames = new ArrayList<>(items.size());
        for (Element item : items) {
            String jid = attr(item, "jid");
            jids.add(jid);
            usernames.add(usernameOf(jid));
        }
        List<Employee> found = orgService.employees(usernames);

        IQ reply = IQ.createResultIQ(request);
        Element el = reply.setChildElement("employees", OrgConstants.NS_ORG);
        for (int i = 0; i < jids.size(); i++) {
            Employee e = found.get(i);
            if (e != null) {
                OrgXml.addEmployee(el, e, domain, null);
            } else {
                OrgXml.addDeletedEmployee(el, jids.get(i));
            }
        }
        return reply;
    }

    /**
     * 组织架构同步（§4.4）。
     *
     * @param request 请求
     * @param child   sync 元素
     * @return 应答
     * @throws SQLException 读取变更日志失败
     */
    private IQ sync(IQ request, Element child) throws SQLException {
        String versionText = attr(child, "version");
        long clientVersion = versionText.isEmpty() ? 0 : parseLong(versionText);
        OrgXml.RsmRequest rsm = OrgXml.parseRsm(child, DEFAULT_SYNC_PAGE);
        OrgService.SyncPage page = orgService.sync(clientVersion, rsm.after(), rsm.max());

        IQ reply = IQ.createResultIQ(request);
        Element el = reply.setChildElement("sync", OrgConstants.NS_ORG)
                .addAttribute("version", Long.toString(page.version()))
                .addAttribute("full", Boolean.toString(page.full()));
        for (OrgService.SyncItem item : page.items()) {
            if (item.isDepartment()) {
                if (item.isDelete()) {
                    OrgXml.addDeletedDepartment(el, item.deletedId());
                } else {
                    OrgXml.addDepartment(el, item.department(), orgService.memberCount(item.department().id()), OrgXml.OP_UPSERT);
                }
            } else if (item.isDelete()) {
                OrgXml.addDeletedEmployee(el, item.deletedId() + "@" + domain);
            } else {
                OrgXml.addEmployee(el, item.employee(), domain, OrgXml.OP_UPSERT);
            }
        }
        List<OrgService.SyncItem> items = page.items();
        OrgXml.addRsmResult(el,
                items.isEmpty() ? null : items.get(0).key(),
                items.isEmpty() ? null : items.get(items.size() - 1).key(),
                page.total());
        Log.debug("组织架构同步：客户端版本 {}，返回 {} 条（全量 {}，共 {} 条），服务器版本 {}",
                clientVersion, items.size(), page.full(), page.total(), page.version());
        return reply;
    }

    /**
     * 搜索同事（§4.5）。
     *
     * @param request 请求
     * @param child   search 元素
     * @return 应答
     */
    private IQ search(IQ request, Element child) {
        String keyword = attr(child, "keyword");
        OrgXml.RsmRequest rsm = OrgXml.parseRsm(child, DEFAULT_SEARCH_PAGE);
        List<Employee> result = orgService.search(keyword, rsm.max());

        IQ reply = IQ.createResultIQ(request);
        Element el = reply.setChildElement("search", OrgConstants.NS_ORG).addAttribute("keyword", keyword);
        for (Employee e : result) {
            OrgXml.addEmployee(el, e, domain, null);
        }
        OrgXml.addRsmResult(el,
                result.isEmpty() ? null : "0",
                result.isEmpty() ? null : Integer.toString(result.size() - 1),
                result.size());
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

    /**
     * 读取属性。
     *
     * @param el   元素
     * @param name 属性名
     * @return 属性值，没有时为空字符串
     */
    private static String attr(Element el, String name) {
        String value = el.attributeValue(name);
        return value == null ? "" : value;
    }

    /**
     * 解析整数。
     *
     * @param text 文本
     * @return 整数
     * @throws IllegalArgumentException 不是整数
     */
    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是整数：" + text);
        }
    }

    /**
     * 解析长整数。
     *
     * @param text 文本
     * @return 长整数
     * @throws IllegalArgumentException 不是整数
     */
    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是整数：" + text);
        }
    }
}
