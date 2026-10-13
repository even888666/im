package cn.android666.im.core.log

/**
 * 日志脱敏：密码、SASL 认证报文、TURN 临时密码等敏感信息**必须**脱敏后再写日志
 * （见 Android工程架构.md §8"敏感信息"）。
 */
object LogSanitizer {

    /** 脱敏后的占位文本 */
    const val MASK = "***"

    /**
     * SASL 认证过程中的元素（RFC 6120 §6.4）：内容是 Base64 编码的认证数据，
     * SCRAM 虽然不直接传明文密码，但可被离线暴力破解，同样不能写日志。
     * 匹配 `<auth ...>内容</auth>`、`<response>`、`<challenge>`、`<success>`，只替换内容。
     */
    private val SASL_ELEMENT = Regex(
        "(<(auth|response|challenge|success)\\b[^>]*>)([^<]+)(</\\2>)",
    )

    /** XEP-0077 注册 / 改密报文中的 `<password>新密码</password>` */
    private val PASSWORD_ELEMENT = Regex("(<password\\b[^>]*>)([^<]*)(</password>)")

    /** 属性形式的密码，例如 XEP-0215 返回的 TURN 凭证 `password='...'` */
    private val PASSWORD_ATTRIBUTE = Regex("(\\bpassword\\s*=\\s*)(['\"])(.*?)\\2")

    /**
     * 对 XMPP 报文脱敏。
     *
     * @param stanza 原始报文
     * @return 脱敏后的报文，敏感内容替换为 [MASK]
     */
    fun sanitizeStanza(stanza: String): String = stanza
        .replace(SASL_ELEMENT) { m -> m.groupValues[1] + MASK + m.groupValues[4] }
        .replace(PASSWORD_ELEMENT) { m -> m.groupValues[1] + MASK + m.groupValues[3] }
        .replace(PASSWORD_ATTRIBUTE) { m -> m.groupValues[1] + m.groupValues[2] + MASK + m.groupValues[2] }

    /**
     * 对单个敏感值脱敏，只保留长度信息，便于排查"是否为空 / 长度是否正确"一类问题。
     *
     * @param secret 敏感值，可为 null
     * @return 例如 `***(len=8)`；null 时返回 `null`
     */
    fun maskSecret(secret: String?): String = if (secret == null) "null" else "$MASK(len=${secret.length})"
}
