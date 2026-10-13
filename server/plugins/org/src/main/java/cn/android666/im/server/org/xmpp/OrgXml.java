package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgConstants;
import cn.android666.im.server.org.model.Department;
import cn.android666.im.server.org.model.Employee;
import org.dom4j.Element;
import org.dom4j.QName;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

/**
 * 组织架构报文的 XML 编解码（《三端协议规范.md》§4.1 的 department / employee 元素、XEP-0059 RSM、§16 错误码）。
 */
public final class OrgXml {

    /** 同步结果中的操作：新增或更新 */
    public static final String OP_UPSERT = "upsert";

    /** 同步结果中的操作：删除 */
    public static final String OP_DELETE = "delete";

    private OrgXml() {
    }

    /**
     * 添加 department 子元素。
     *
     * @param parent      父元素
     * @param department  部门
     * @param memberCount 直属在职人数
     * @param op          操作（upsert），不在同步结果中时传 null
     * @return 新元素
     */
    public static Element addDepartment(Element parent, Department department, int memberCount, String op) {
        Element el = parent.addElement("department")
                .addAttribute("id", department.id())
                .addAttribute("parentId", department.parentId())
                .addAttribute("name", department.name())
                .addAttribute("sort", Integer.toString(department.sort()))
                .addAttribute("memberCount", Integer.toString(memberCount));
        if (op != null) {
            el.addAttribute("op", op);
        }
        return el;
    }

    /**
     * 添加被删除部门的 department 子元素（只有 id 和 op）。
     *
     * @param parent 父元素
     * @param id     部门 ID
     * @return 新元素
     */
    public static Element addDeletedDepartment(Element parent, String id) {
        return parent.addElement("department").addAttribute("id", id).addAttribute("op", OP_DELETE);
    }

    /**
     * 添加 employee 子元素。
     *
     * @param parent   父元素
     * @param employee 员工
     * @param domain   XMPP 域名（拼接 JID）
     * @param op       操作（upsert），不在同步结果中时传 null
     * @return 新元素
     */
    public static Element addEmployee(Element parent, Employee employee, String domain, String op) {
        Element el = parent.addElement("employee")
                .addAttribute("jid", employee.username() + "@" + domain)
                .addAttribute("empNo", employee.username())
                .addAttribute("name", employee.name())
                .addAttribute("pinyin", employee.pinyin())
                .addAttribute("initials", employee.initials())
                .addAttribute("deptId", employee.deptId())
                .addAttribute("title", employee.title())
                .addAttribute("mobile", employee.mobile())
                .addAttribute("phone", employee.phone())
                .addAttribute("email", employee.email())
                .addAttribute("sort", Integer.toString(employee.sort()))
                .addAttribute("avatarHash", employee.avatarHash());
        if (op != null) {
            el.addAttribute("op", op);
        }
        return el;
    }

    /**
     * 添加不存在 / 已离职员工的 employee 子元素（只有 jid 和 op='delete'）。
     *
     * @param parent 父元素
     * @param jid    员工 bare JID
     * @return 新元素
     */
    public static Element addDeletedEmployee(Element parent, String jid) {
        return parent.addElement("employee").addAttribute("jid", jid).addAttribute("op", OP_DELETE);
    }

    /**
     * RSM 请求参数。
     *
     * @param max   每页条数（已按默认值和上限处理）
     * @param after 上一页最后一项的游标，第一页为 null
     */
    public record RsmRequest(int max, String after) {
    }

    /**
     * 解析 RSM 请求（{@code <set xmlns='http://jabber.org/protocol/rsm'>}）。
     *
     * @param parent     请求的子元素（dept-children、sync 等）
     * @param defaultMax 没有 max 时的默认值
     * @return 请求参数，max 不超过 {@link OrgConstants#MAX_PAGE_SIZE}
     * @throws IllegalArgumentException max 不是正整数
     */
    public static RsmRequest parseRsm(Element parent, int defaultMax) {
        Element set = parent.element(QName.get("set", OrgConstants.NS_RSM));
        int max = defaultMax;
        String after = null;
        if (set != null) {
            String maxText = set.elementTextTrim("max");
            if (maxText != null && !maxText.isEmpty()) {
                try {
                    max = Integer.parseInt(maxText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("max 不是整数：" + maxText);
                }
                if (max <= 0) {
                    throw new IllegalArgumentException("max 必须大于 0：" + max);
                }
            }
            String afterText = set.elementText("after");
            if (afterText != null && !afterText.isEmpty()) {
                after = afterText;
            }
        }
        return new RsmRequest(Math.min(max, OrgConstants.MAX_PAGE_SIZE), after);
    }

    /**
     * 添加 RSM 结果（{@code <set><first/><last/><count/></set>}）。本页为空时只有 count。
     *
     * @param parent 父元素
     * @param first  本页第一项的游标，本页为空时为 null
     * @param last   本页最后一项的游标，本页为空时为 null
     * @param count  全部结果的数量
     * @return set 元素
     */
    public static Element addRsmResult(Element parent, String first, String last, int count) {
        Element set = parent.addElement(QName.get("set", OrgConstants.NS_RSM));
        if (first != null) {
            set.addElement("first").setText(first);
            set.addElement("last").setText(last);
        }
        set.addElement("count").setText(Integer.toString(count));
        return set;
    }

    /**
     * 创建错误应答：标准错误条件 + 可选的 {@code <app-error xmlns='urn:android666:im:error:1' code='...'/>}（§16）。
     *
     * @param request   请求
     * @param condition 标准错误条件
     * @param appCode   自定义错误码，没有时为 null
     * @return 错误应答
     */
    public static IQ errorReply(IQ request, PacketError.Condition condition, String appCode) {
        IQ reply = IQ.createResultIQ(request);
        if (request.getChildElement() != null) {
            reply.setChildElement(request.getChildElement().createCopy());
        }
        reply.setError(error(condition, appCode));
        return reply;
    }

    /**
     * 创建带自定义错误码的 stanza 错误。
     *
     * @param condition 标准错误条件
     * @param appCode   自定义错误码，没有时为 null
     * @return 错误
     */
    public static PacketError error(PacketError.Condition condition, String appCode) {
        PacketError error = new PacketError(condition);
        if (appCode != null) {
            error.getElement().addElement(QName.get("app-error", OrgConstants.NS_ERROR)).addAttribute("code", appCode);
        }
        return error;
    }
}
