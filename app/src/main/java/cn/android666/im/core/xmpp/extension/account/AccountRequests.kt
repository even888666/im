package cn.android666.im.core.xmpp.extension.account

import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.Xml
import cn.android666.im.core.xmpp.iq.XmlNode

/**
 * 账号状态（协议规范 §3.1）。
 *
 * @property mustChangePassword 为 true 时客户端必须进入强制改密页面，改密成功前不进入主界面
 */
data class AccountStatus(val mustChangePassword: Boolean)

/**
 * 查询账号状态（协议规范 §3.1）：
 * ```
 * <iq type='get' to='im.example.com'><account xmlns='urn:android666:im:account:1'/></iq>
 * ```
 */
class AccountStatusRequest : IqRequest<AccountStatus>(IqType.GET, ELEMENT, NAMESPACE) {

    /**
     * 解析 `<account mustChangePassword='true'/>`；属性缺失时视为不需要改密。
     *
     * @param result 结果子元素
     * @return 账号状态
     */
    override fun parseResult(result: XmlNode?): AccountStatus {
        require(result != null && result.name == ELEMENT && result.namespace == NAMESPACE) { "账号状态结果格式错误：$result" }
        return AccountStatus(mustChangePassword = result.boolAttr("mustChangePassword"))
    }

    companion object {
        /** 命名空间 */
        const val NAMESPACE = "urn:android666:im:account:1"

        /** 元素名 */
        const val ELEMENT = "account"
    }
}

/**
 * 修改密码（协议规范 §3.2，XEP-0077）：
 * ```
 * <iq type='set' to='im.example.com'>
 *   <query xmlns='jabber:iq:register'><username>10086</username><password>新密码</password></query>
 * </iq>
 * ```
 * 成功时服务器返回空结果；密码不符合规则时返回 not-acceptable + 错误码 weak-password（协议规范 §16）。
 *
 * @property username 工号
 * @property newPassword 新密码
 */
class ChangePasswordRequest(
    private val username: String,
    private val newPassword: String,
) : IqRequest<Unit>(IqType.SET, "query", NAMESPACE) {

    override fun childrenXml(): String =
        "<username>${Xml.escape(username)}</username><password>${Xml.escape(newPassword)}</password>"

    /** 成功时为空结果，不需要解析 */
    override fun parseResult(result: XmlNode?) = Unit

    /** 不输出密码 */
    override fun toString(): String = "ChangePasswordRequest(username=$username, password=***)"

    companion object {
        /** XEP-0077 命名空间 */
        const val NAMESPACE = "jabber:iq:register"
    }
}
