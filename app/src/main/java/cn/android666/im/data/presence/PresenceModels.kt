package cn.android666.im.data.presence

import cn.android666.im.core.xmpp.OwnPresence

/**
 * 本人可选的在线状态（协议规范 §5.2 的 code 与 show 对应关系）。
 *
 * @property code 自定义状态码；在线时为 null
 * @property show 对应的 show；在线时为 null
 */
enum class MyStatus(val code: String?, val show: String?) {
    /** 在线 */
    ONLINE(null, null),

    /** 忙碌 */
    BUSY("busy", "dnd"),

    /** 开会中 */
    MEETING("meeting", "dnd"),

    /** 出差 */
    BUSINESS_TRIP("business-trip", "away"),

    /** 离开 */
    AWAY("away", "away"),
    ;

    companion object {
        /**
         * 按状态码查找。
         *
         * @param code 状态码
         * @return 状态；未知的状态码为 null
         */
        fun fromCode(code: String?): MyStatus? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 本人当前的在线状态设置。
 *
 * @property status 状态
 * @property text 自定义文字，没有时为空字符串
 */
data class MyStatusSetting(val status: MyStatus = MyStatus.ONLINE, val text: String = "") {

    /**
     * 转为要发送的 presence。
     *
     * @return presence
     */
    fun toOwnPresence(): OwnPresence = OwnPresence(show = status.show, status = text.ifBlank { null }, code = status.code)
}

/**
 * 同事某个端的在线状态。
 *
 * @property resource 端：android / pc / web
 * @property status 状态（按 code 识别，不认识的 code 按 show 识别，协议规范 §5.2）
 * @property text 自定义文字
 */
data class ResourceStatus(val resource: String, val status: MyStatus, val text: String?)

/**
 * 同事的在线状态（协议规范 §5.3 查询结果）。
 *
 * @property jid bare JID
 * @property online 是否有任一端在线
 * @property resources 在线的端
 * @property lastActiveMillis 离线时最后活跃的时间；未知时为 null
 */
data class ColleaguePresence(
    val jid: String,
    val online: Boolean,
    val resources: List<ResourceStatus>,
    val lastActiveMillis: Long?,
) {
    /** 综合状态：多个端在线时取"最忙"的（忙碌 / 开会 > 出差 / 离开 > 在线），离线时为 null */
    val overall: MyStatus?
        get() = if (!online) null else resources.map { it.status }.maxByOrNull { PRIORITY.indexOf(it) } ?: MyStatus.ONLINE

    private companion object {
        /** 状态的"忙碌程度"，越靠后越优先显示 */
        val PRIORITY = listOf(MyStatus.ONLINE, MyStatus.AWAY, MyStatus.BUSINESS_TRIP, MyStatus.BUSY, MyStatus.MEETING)
    }
}

/**
 * 按协议规范 §5.2 识别状态：先按 code，不认识的 code 按 show（dnd → 忙碌，away / xa → 离开，其他 → 在线）。
 *
 * @param code 状态码
 * @param show show
 * @return 状态
 */
internal fun statusOf(code: String?, show: String?): MyStatus = MyStatus.fromCode(code)?.takeIf { code != null } ?: when (show) {
    "dnd" -> MyStatus.BUSY
    "away", "xa" -> MyStatus.AWAY
    else -> MyStatus.ONLINE
}
