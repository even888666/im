package cn.android666.im.ui.conversation

import cn.android666.im.data.conversation.ConversationItem
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.LastMessageState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** [ConversationDisplay] 测试：时间标签、预览、名称、角标 */
class ConversationDisplayTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-13（星期二）15:00 北京时间 */
    private val now = millis(2026, 10, 13, 15, 0)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun label(time: Long) = ConversationDisplay.timeLabel(time, now, zone)

    @Test
    fun timeLabel_rules() {
        assertThat(label(0)).isEqualTo(TimeLabel.None)
        assertThat(label(millis(2026, 10, 13, 0, 5))).isEqualTo(TimeLabel.Today(0, 5))
        // 时钟偏差导致的"未来"时间按今天显示
        assertThat(label(millis(2026, 10, 13, 15, 3))).isEqualTo(TimeLabel.Today(15, 3))
        assertThat(label(millis(2026, 10, 12, 23, 59))).isEqualTo(TimeLabel.Yesterday)
        // 6 天前（星期三）显示星期；7 天前显示日期
        assertThat(label(millis(2026, 10, 7, 9, 0))).isEqualTo(TimeLabel.Weekday(3))
        assertThat(label(millis(2026, 10, 6, 9, 0))).isEqualTo(TimeLabel.ThisYear(10, 6))
        assertThat(label(millis(2026, 1, 1, 0, 0))).isEqualTo(TimeLabel.ThisYear(1, 1))
        assertThat(label(millis(2025, 12, 31, 23, 0))).isEqualTo(TimeLabel.Older(2025, 12, 31))
    }

    /** 跨时区：按设备时区计算"今天" */
    @Test
    fun timeLabel_usesZone() {
        // 北京时间 10-13 07:00 = UTC 10-12 23:00
        val time = millis(2026, 10, 13, 7, 0)
        assertThat(ConversationDisplay.timeLabel(time, now, ZoneId.of("UTC"))).isEqualTo(TimeLabel.Yesterday)
    }

    private fun item(
        unread: Int = 0,
        mentionMe: Boolean = false,
        draft: String = "",
        kind: ConversationKind = ConversationKind.SINGLE,
        peerName: String? = "张三",
    ) = ConversationItem(
        id = "10002@im.example.com", kind = kind, peerName = peerName, avatarHash = "", preview = "最后一条",
        lastMessageState = LastMessageState.NONE, time = 1, unreadCount = unread, mentionMe = mentionMe, draft = draft,
        pinned = false, muted = false,
    )

    @Test
    fun preview_priority() {
        assertThat(ConversationDisplay.preview(item())).isEqualTo(PreviewParts(null, "最后一条", showState = true))
        assertThat(ConversationDisplay.preview(item(draft = "没写完"))).isEqualTo(PreviewParts(PreviewPrefix.DRAFT, "没写完", false))
        // 有人 @ 我（未读期间）优先于草稿
        assertThat(ConversationDisplay.preview(item(unread = 2, mentionMe = true, draft = "x")))
            .isEqualTo(PreviewParts(PreviewPrefix.MENTION, "最后一条", false))
        // 已读后不再提示 @
        assertThat(ConversationDisplay.preview(item(unread = 0, mentionMe = true)).prefix).isNull()
    }

    @Test
    fun plainName() {
        assertThat(ConversationDisplay.plainName(item())).isEqualTo("张三")
        // 已离职 / 未同步：显示工号
        assertThat(ConversationDisplay.plainName(item(peerName = null))).isEqualTo("10002")
        assertThat(ConversationDisplay.plainName(item(kind = ConversationKind.SELF))).isNull()
        assertThat(ConversationDisplay.plainName(item(kind = ConversationKind.NOTICE))).isNull()
    }

    @Test
    fun badgeText() {
        assertThat(ConversationDisplay.badgeText(0)).isNull()
        assertThat(ConversationDisplay.badgeText(-1)).isNull()
        assertThat(ConversationDisplay.badgeText(1)).isEqualTo("1")
        assertThat(ConversationDisplay.badgeText(99)).isEqualTo("99")
        assertThat(ConversationDisplay.badgeText(100)).isEqualTo("99+")
    }
}
