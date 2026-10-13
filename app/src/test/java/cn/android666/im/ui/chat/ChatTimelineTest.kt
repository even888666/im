package cn.android666.im.ui.chat

import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.message.ChatMessage
import cn.android666.im.data.message.MessageState
import cn.android666.im.ui.conversation.TimeLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** [ChatTimeline] 测试：时间分隔的插入规则与显示内容 */
class ChatTimelineTest {

    private fun msg(rowId: Long, time: Long) = ChatMessage(
        rowId = rowId, msgId = "m$rowId", conversationId = "c", outgoing = false, senderJid = "c", senderName = null,
        senderAvatarHash = "", content = MessageContent.Text, body = "x", state = MessageState.RECEIVED, errorCode = null,
        serverTime = time, replyToMsgId = null, retracted = false, retractedBy = null,
    )

    @Test
    fun separator_rules() {
        val gap = ChatTimeline.GAP_MILLIS
        // 列表开头（最新一条的下方）不插入
        assertThat(ChatTimeline.separator(null, msg(1, 0))).isNull()
        // 间隔不超过 5 分钟不插入；超过时插在较新那条的上方
        assertThat(ChatTimeline.separator(msg(2, gap), msg(1, 0))).isNull()
        assertThat(ChatTimeline.separator(msg(2, gap + 1), msg(1, 0))).isEqualTo(ChatListItem.TimeHeader(gap + 1, 2))
        // 已加载到最早一条：在它上方插入
        assertThat(ChatTimeline.separator(msg(1, 100), null)).isEqualTo(ChatListItem.TimeHeader(100, 1))
    }

    @Test
    fun label_dayAndTime() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 10, 13, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 10, 12, 9, 7, 0, 0, zone).toInstant().toEpochMilli()

        assertThat(ChatTimeline.label(yesterday, now, zone)).isEqualTo(ChatTimeLabel(TimeLabel.Yesterday, 9, 7))
        assertThat(ChatTimeline.label(now, now, zone)).isEqualTo(ChatTimeLabel(TimeLabel.Today(15, 0), 15, 0))
    }

    @Test
    fun keys_unique() {
        assertThat(ChatListItem.Message(msg(5, 0)).key).isEqualTo("m5")
        assertThat(ChatListItem.TimeHeader(0, 5).key).isEqualTo("t5")
    }
}
