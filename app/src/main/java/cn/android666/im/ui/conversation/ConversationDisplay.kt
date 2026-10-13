package cn.android666.im.ui.conversation

import cn.android666.im.data.conversation.ConversationItem
import cn.android666.im.data.conversation.ConversationKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 会话列表的时间标签（与微信一致），由界面转为文字。
 */
sealed interface TimeLabel {

    /** 没有时间（会话没有消息） */
    data object None : TimeLabel

    /**
     * 今天：显示时分。
     *
     * @property hour 时
     * @property minute 分
     */
    data class Today(val hour: Int, val minute: Int) : TimeLabel

    /** 昨天 */
    data object Yesterday : TimeLabel

    /**
     * 一周内：显示星期。
     *
     * @property dayOfWeek 1 ~ 7（星期一 ~ 星期日）
     */
    data class Weekday(val dayOfWeek: Int) : TimeLabel

    /**
     * 今年：显示月日。
     *
     * @property month 月
     * @property day 日
     */
    data class ThisYear(val month: Int, val day: Int) : TimeLabel

    /**
     * 更早：显示年月日。
     *
     * @property year 年
     * @property month 月
     * @property day 日
     */
    data class Older(val year: Int, val month: Int, val day: Int) : TimeLabel
}

/** 预览文字前的提示 */
enum class PreviewPrefix {
    /** "[有人@我]" */
    MENTION,

    /** "[草稿]" */
    DRAFT,
}

/**
 * 预览的组成。
 *
 * @property prefix 前缀提示；没有时为 null
 * @property text 正文
 * @property showState 是否显示最后一条消息的发送状态图标（显示草稿时不显示）
 */
data class PreviewParts(val prefix: PreviewPrefix?, val text: String, val showState: Boolean)

/**
 * 会话列表的显示规则（纯逻辑，便于单元测试）。
 */
object ConversationDisplay {

    /**
     * 计算时间标签：今天显示时分，昨天显示"昨天"，7 天内显示星期，今年显示月日，更早显示年月日。
     *
     * @param time 消息时间（毫秒）；0 表示没有
     * @param now 当前时间（毫秒）
     * @param zone 时区
     * @return 时间标签
     */
    fun timeLabel(time: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): TimeLabel {
        if (time <= 0) return TimeLabel.None
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), zone)
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        val date: LocalDate = at.toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(date, today)
        return when {
            // 未来的时间（时钟偏差）也按今天显示
            daysAgo <= 0 -> TimeLabel.Today(at.hour, at.minute)
            daysAgo == 1L -> TimeLabel.Yesterday
            daysAgo < 7 -> TimeLabel.Weekday(at.dayOfWeek.value)
            date.year == today.year -> TimeLabel.ThisYear(date.monthValue, date.dayOfMonth)
            else -> TimeLabel.Older(date.year, date.monthValue, date.dayOfMonth)
        }
    }

    /**
     * 计算预览：未读期间有人 @ 我时优先提示；有草稿时显示草稿；否则显示最后一条消息。
     *
     * @param item 会话
     * @return 预览的组成
     */
    fun preview(item: ConversationItem): PreviewParts = when {
        item.mentionMe && item.unreadCount > 0 -> PreviewParts(PreviewPrefix.MENTION, item.preview, showState = false)
        item.draft.isNotEmpty() -> PreviewParts(PreviewPrefix.DRAFT, item.draft, showState = false)
        else -> PreviewParts(null, item.preview, showState = true)
    }

    /**
     * 名称中不依赖资源的部分：单聊优先组织架构姓名，找不到（已离职、未同步）时显示工号；群聊暂时显示群 ID（M6 换成群名）。
     * 文件传输助手、公司公告的名称由界面取字符串资源。
     *
     * @param item 会话
     * @return 名称；需要界面取固定文字时为 null
     */
    fun plainName(item: ConversationItem): String? = when (item.kind) {
        ConversationKind.SELF, ConversationKind.NOTICE -> null
        ConversationKind.SINGLE, ConversationKind.GROUP -> item.peerName ?: item.id.substringBefore('@')
    }

    /**
     * 角标文字：超过 99 显示"99+"。
     *
     * @param count 未读数
     * @return 文字；没有未读时为 null
     */
    fun badgeText(count: Int): String? = when {
        count <= 0 -> null
        count > MAX_BADGE -> "$MAX_BADGE+"
        else -> count.toString()
    }

    /** 角标显示的最大数字 */
    private const val MAX_BADGE = 99
}
