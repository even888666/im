package cn.android666.im.core.xmpp

/**
 * XMPP 服务器地址配置。
 *
 * @property domain XMPP 域名（协议规范 §1.1），也用于 TLS 主机名校验
 * @property host 实际连接的主机名或 IP；为 null 时直接连接 [domain]（由 DNS 解析）
 * @property port 客户端端口，默认 5222（STARTTLS，协议规范 §2.1）
 * @property resource 端标识，Android 固定为 `android`（协议规范 §1.1）
 */
data class ServerConfig(
    val domain: String,
    val host: String? = null,
    val port: Int = DEFAULT_PORT,
    val resource: String = RESOURCE_ANDROID,
) {
    companion object {
        /** 客户端默认端口 */
        const val DEFAULT_PORT = 5222

        /** Android 端的 resource */
        const val RESOURCE_ANDROID = "android"
    }
}
