package cn.android666.im.core.xmpp

/**
 * 本人发送的在线状态（协议规范 §5.2）：
 * ```
 * <presence><show>dnd</show><status>下午 3 点前在开会</status><state xmlns='urn:android666:im:presence:1' code='meeting'/></presence>
 * ```
 *
 * @property show away / dnd；null 表示在线（省略 show）
 * @property status 自定义文字，没有时为 null
 * @property code 自定义状态码（busy、meeting、business-trip、away），在线时为 null（不带 state 元素）
 */
data class OwnPresence(val show: String? = null, val status: String? = null, val code: String? = null) {

    companion object {
        /** 在线（默认） */
        val AVAILABLE = OwnPresence()

        /** 自定义状态的命名空间 */
        const val NAMESPACE = "urn:android666:im:presence:1"
    }
}
