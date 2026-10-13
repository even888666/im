package cn.android666.im.server.org;

/**
 * 组织架构插件使用的命名空间、错误码和限制（《三端协议规范.md》§1.6、§3 ~ §5、§16、附录 A）。
 */
public final class OrgConstants {

    /** 账号状态（§3） */
    public static final String NS_ACCOUNT = "urn:android666:im:account:1";

    /** 组织架构（§4） */
    public static final String NS_ORG = "urn:android666:im:org:1";

    /** 自定义在线状态与在线状态查询（§5） */
    public static final String NS_PRESENCE = "urn:android666:im:presence:1";

    /** 自定义错误码（§16） */
    public static final String NS_ERROR = "urn:android666:im:error:1";

    /** 结果集分页（XEP-0059） */
    public static final String NS_RSM = "http://jabber.org/protocol/rsm";

    /** 错误码：未改初始密码时发送消息 */
    public static final String ERROR_MUST_CHANGE_PASSWORD = "must-change-password";

    /** 错误码：新密码不符合规则 */
    public static final String ERROR_WEAK_PASSWORD = "weak-password";

    /** 错误码：批量查询超过上限 */
    public static final String ERROR_TOO_MANY_ITEMS = "too-many-items";

    /** stream 级错误：账号已禁用（§2.4） */
    public static final String STREAM_ERROR_ACCOUNT_DISABLED = "account-disabled";

    /** 批量查询（员工详情、在线状态）每次的上限 */
    public static final int MAX_BATCH = 100;

    /** RSM 分页每页的上限 */
    public static final int MAX_PAGE_SIZE = 500;

    private OrgConstants() {
    }
}
