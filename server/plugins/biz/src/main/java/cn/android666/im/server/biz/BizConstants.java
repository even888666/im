package cn.android666.im.server.biz;

/**
 * 业务扩展插件用到的命名空间与错误码（《三端协议规范.md》§1.2、§16）。
 */
public final class BizConstants {

    private BizConstants() {
    }

    /** 自定义错误码命名空间 */
    public static final String NS_ERROR = "urn:android666:im:error:1";

    /** XEP-0424 撤回 */
    public static final String NS_RETRACT = "urn:xmpp:message-retract:1";

    /** XEP-0359 origin-id */
    public static final String NS_SID = "urn:xmpp:sid:0";

    /** 错误码：消息包含敏感词（policy-violation） */
    public static final String ERROR_SENSITIVE_WORD = "sensitive-word";

    /** 错误码：超过 2 分钟撤回（not-allowed） */
    public static final String ERROR_RETRACT_TIMEOUT = "retract-timeout";

    /** 错误码：撤回他人消息且非群管理（forbidden） */
    public static final String ERROR_RETRACT_FORBIDDEN = "retract-forbidden";
}
