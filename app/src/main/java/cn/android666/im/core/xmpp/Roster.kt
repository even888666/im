package cn.android666.im.core.xmpp

/**
 * 花名册（Roster）条目的订阅状态（RFC 6121）。常用联系人 = 本人花名册中为 [TO] 或 [BOTH] 的条目（协议规范 §5.1）。
 */
enum class RosterSubscription {
    /** 双方都没有订阅 */
    NONE,

    /** 我订阅了对方（对方是我的常用联系人） */
    TO,

    /** 对方订阅了我（只有这个状态的条目不显示） */
    FROM,

    /** 互相订阅 */
    BOTH,

    /** 条目已从花名册中删除（只出现在花名册推送中） */
    REMOVE,
    ;

    /** 是否为常用联系人（我订阅了对方） */
    val isContact: Boolean get() = this == TO || this == BOTH
}

/**
 * 花名册条目。
 *
 * @property jid 联系人 bare JID
 * @property subscription 订阅状态
 */
data class RosterItem(val jid: String, val subscription: RosterSubscription)
