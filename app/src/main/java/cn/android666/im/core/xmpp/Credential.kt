package cn.android666.im.core.xmpp

/**
 * 登录凭证。
 *
 * @property username 工号（即 JID 的用户名部分，协议规范 §1.1）
 * @property password 密码；只在内存中短暂持有，持久化时必须加密（Android工程架构.md §4、功能清单 §14）
 */
class Credential(val username: String, val password: String) {

    /** 不输出密码，防止被意外写进日志 */
    override fun toString(): String = "Credential(username=$username, password=***)"
}

/**
 * 登录 / 重连的结果。
 *
 * @property resumed 是否通过流恢复回到原会话（见 [ConnectionState.Authenticated.resumed]）
 */
data class LoginResult(val resumed: Boolean)
