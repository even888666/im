package cn.android666.im.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.R
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.message.ChatMessage
import cn.android666.im.data.message.MessageState
import cn.android666.im.data.message.ReplyPreview
import cn.android666.im.ui.conversation.TimeLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ChatAdapter] 的显示规则测试 */
@RunWith(RobolectricTestRunner::class)
class ChatAdapterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readStateText() {
        assertThat(ChatAdapter.readStateText(MessageState.SENDING)).isNull()
        assertThat(ChatAdapter.readStateText(MessageState.FAILED)).isNull()
        assertThat(ChatAdapter.readStateText(MessageState.SENT)).isEqualTo(R.string.chat_unread)
        assertThat(ChatAdapter.readStateText(MessageState.DELIVERED)).isEqualTo(R.string.chat_unread)
        assertThat(ChatAdapter.readStateText(MessageState.READ)).isEqualTo(R.string.chat_read)
    }

    @Test
    fun errorText_onlySensitiveWord() {
        fun msg(state: MessageState, code: String?) = ChatMessage(
            rowId = 1, msgId = "m", conversationId = "c", outgoing = true, senderJid = "s", senderName = null,
            senderAvatarHash = "", content = MessageContent.Text, body = "x", state = state, errorCode = code,
            serverTime = 0, replyToMsgId = null, retracted = false, retractedBy = null,
        )
        assertThat(ChatAdapter.errorText(msg(MessageState.FAILED, "sensitive-word"))).isEqualTo(R.string.chat_error_sensitive)
        assertThat(ChatAdapter.errorText(msg(MessageState.FAILED, "timeout"))).isNull()
        assertThat(ChatAdapter.errorText(msg(MessageState.SENT, "sensitive-word"))).isNull()
    }

    @Test
    fun timeHeaderText() {
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.Today(9, 5), 9, 5))).isEqualTo("09:05")
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.Yesterday, 23, 0))).isEqualTo("昨天 23:00")
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.Weekday(1), 8, 30))).isEqualTo("星期一 08:30")
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.ThisYear(10, 6), 8, 30))).isEqualTo("10月6日 08:30")
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.Older(2025, 1, 2), 8, 30)))
            .isEqualTo("2025年1月2日 08:30")
    }

    @Test
    fun quoteText() {
        val self = "10001@im.example.com"
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Found("10002@im.example.com", "李四", "原文"), self)).isEqualTo("李四：原文")
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Found("10002@im.example.com", null, "原文"), self)).isEqualTo("10002：原文")
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Found(self, "张三", "我说的"), self)).isEqualTo("我：我说的")
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Retracted, self)).isEqualTo("原消息已撤回")
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Missing, self)).isEqualTo("原消息不可见")
    }
}
