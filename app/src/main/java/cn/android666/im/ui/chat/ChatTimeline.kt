package cn.android666.im.ui.chat

import cn.android666.im.data.message.ChatMessage
import cn.android666.im.ui.conversation.ConversationDisplay
import cn.android666.im.ui.conversation.TimeLabel
import java.time.Instant
import java.time.ZoneId

/** 聊天页列表中的一项：消息或时间分隔 */
sealed interface ChatListItem {

    /** 稳定的 key（DiffUtil 判断是否同一项） */
    val key: String

    /**
     * 消息。
     *
     * @property message 消息
     */
    data class Message(val message: ChatMessage) : ChatListItem {
        override val key: String get() = "m${message.rowId}"
    }

    /**
     * 时间分隔（显示在下方第一条消息的上面）。
     *
     * @property time 下方第一条消息的时间（毫秒）
     * @property belowRowId 下方第一条消息的 rowId（保证 key 唯一）
     */
    data class TimeHeader(val time: Long, val belowRowId: Long) : ChatListItem {
        override val key: String get() = "t$belowRowId"
    }
}

/**
 * 聊天页时间分隔的显示文字的组成：日期部分 + 时分。
 *
 * @property day 日期部分（今天时为 [TimeLabel.Today]，只显示时分）
 * @property hour 时
 * @property minute 分
 */
data class ChatTimeLabel(val day: TimeLabel, val hour: Int, val minute: Int)

/**
 * 聊天页的时间分隔规则（纯逻辑，便于单元测试）。
 */
object ChatTimeline {

    /** 相邻两条消息间隔超过 5 分钟时显示时间（决策记录 A90） */
    const val GAP_MILLIS = 5 * 60 * 1000L

    /**
     * 两条相邻消息之间是否插入时间分隔。列表按时间倒序（新的在前），分隔显示在较新那条的上方。
     *
     * @param newer 较新的消息；为 null 表示已到列表开头（最新一条之后），不插入
     * @param older 较早的消息；为 null 表示已加载到最早一条，此时在最早一条上方插入
     * @return 需要插入时为分隔项，否则为 null
     */
    fun separator(newer: ChatMessage?, older: ChatMessage?): ChatListItem.TimeHeader? {
        if (newer == null) return null
        if (older != null && newer.serverTime - older.serverTime <= GAP_MILLIS) return null
        return ChatListItem.TimeHeader(newer.serverTime, newer.rowId)
    }

    /**
     * 时间分隔的显示内容：今天只显示时分，其余显示"昨天 / 星期 / 日期"加时分。
     *
     * @param time 时间（毫秒）
     * @param now 当前时间（毫秒）
     * @param zone 时区
     * @return 显示内容
     */
    fun label(time: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): ChatTimeLabel {
        val at = Instant.ofEpochMilli(time).atZone(zone)
        return ChatTimeLabel(ConversationDisplay.timeLabel(time, now, zone), at.hour, at.minute)
    }
}
