package cn.android666.im.core.xmpp

/**
 * XMPP 操作失败的异常。业务代码只处理这些类型，不接触 Smack 的异常（Android工程架构.md §2.1）。
 *
 * @param message 描述，写日志用
 * @param cause 底层异常
 */
sealed class XmppException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 账号或密码错误，或账号已被禁用（SASL `not-authorized`，协议规范 §2.4） */
    class AuthenticationFailed(cause: Throwable? = null) :
        XmppException("认证失败：账号或密码错误，或账号已被禁用", cause)

    /**
     * 网络连接失败：无法连接服务器、连接中断、读写出错。可以重试。
     *
     * @param detail 失败详情
     */
    class ConnectionFailed(detail: String, cause: Throwable? = null) :
        XmppException("连接失败：$detail", cause)

    /** TLS 协商失败，例如服务器证书不受信任或主机名不匹配。不应重试，需检查服务器证书配置 */
    class TlsFailed(cause: Throwable? = null) :
        XmppException("TLS 安全连接失败：服务器证书不受信任或主机名不匹配", cause)

    /** 请求在超时时间内没有响应 */
    class Timeout(cause: Throwable? = null) : XmppException("请求超时", cause)

    /**
     * 服务器的响应不符合协议格式（解析失败）。通常是客户端与服务端插件版本不匹配。
     *
     * @param detail 详情
     */
    class InvalidResponse(detail: String, cause: Throwable? = null) : XmppException("响应格式错误：$detail", cause)

    /** 当前未连接，无法执行操作 */
    class NotConnected(cause: Throwable? = null) : XmppException("未连接服务器", cause)

    /** 同一端在其他设备登录（协议规范 §2.4） */
    class Conflict(cause: Throwable? = null) : XmppException("账号已在其他设备登录", cause)

    /** 账号已被管理员禁用（协议规范 §2.4） */
    class AccountDisabled(cause: Throwable? = null) : XmppException("账号已被禁用", cause)

    /**
     * 服务器返回了 stanza error（协议规范 §16）。
     *
     * @property condition 标准错误条件，例如 `forbidden`、`not-acceptable`
     * @property appErrorCode 自定义错误码（`<app-error xmlns='urn:android666:im:error:1' code='...'/>`），没有时为 null
     */
    class StanzaError(val condition: String, val appErrorCode: String?, cause: Throwable? = null) :
        XmppException("服务器返回错误：$condition${appErrorCode?.let { "（$it）" } ?: ""}", cause)
}
