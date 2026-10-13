package cn.android666.im.ui.conversation

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.R
import cn.android666.im.data.conversation.ConversationItem
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.LastMessageState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** [ConversationAdapter] 测试：列表项的名称、预览前缀、角标、状态图标 */
@RunWith(RobolectricTestRunner::class)
class ConversationAdapterTest {

    private val context: Context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Im)

    private val clicked = mutableListOf<String>()
    private val longClicked = mutableListOf<String>()

    private val adapter = ConversationAdapter(
        now = { 0 },
        onClick = { clicked += it.id },
        onLongClick = { _, name -> longClicked += name },
    )

    private fun item(
        id: String = "10002@im.example.com",
        kind: ConversationKind = ConversationKind.SINGLE,
        unread: Int = 0,
        muted: Boolean = false,
        mentionMe: Boolean = false,
        state: LastMessageState = LastMessageState.NONE,
    ) = ConversationItem(
        id = id, kind = kind, peerName = "李四".takeIf { kind == ConversationKind.SINGLE }, avatarHash = "", preview = "你好",
        lastMessageState = state, time = 0, unreadCount = unread, mentionMe = mentionMe, draft = "", pinned = false, muted = muted,
    )

    /** 提交列表并绑定第 position 项（先清空：从空列表提交是同步的，否则 DiffUtil 在后台线程计算） */
    private fun bind(items: List<ConversationItem>, position: Int = 0): ConversationAdapter.Holder {
        adapter.submitList(null)
        adapter.submitList(items)
        shadowOf(Looper.getMainLooper()).idle()
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, position)
        return holder
    }

    @Test
    fun single_withUnreadAndFailed() {
        val b = bind(listOf(item(unread = 120, state = LastMessageState.FAILED))).binding

        assertThat(b.name.text.toString()).isEqualTo("李四")
        assertThat(b.preview.text.toString()).isEqualTo("你好")
        assertThat(b.unreadBadge.visibility).isEqualTo(View.VISIBLE)
        assertThat(b.unreadBadge.text.toString()).isEqualTo("99+")
        assertThat(b.unreadDot.visibility).isEqualTo(View.GONE)
        assertThat(b.stateIcon.visibility).isEqualTo(View.VISIBLE)
        assertThat(b.time.text.toString()).isEmpty()

        b.root.performClick()
        b.root.performLongClick()
        assertThat(clicked).containsExactly("10002@im.example.com")
        assertThat(longClicked).containsExactly("李四")
    }

    /** 免打扰：小红点代替数字，显示免打扰图标 */
    @Test
    fun muted_dotInsteadOfNumber() {
        val b = bind(listOf(item(unread = 3, muted = true))).binding

        assertThat(b.unreadBadge.visibility).isEqualTo(View.GONE)
        assertThat(b.unreadDot.visibility).isEqualTo(View.VISIBLE)
        assertThat(b.mutedIcon.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun selfAndMention() {
        val self = bind(listOf(item(id = "10001@im.example.com", kind = ConversationKind.SELF))).binding
        assertThat(self.name.text.toString()).isEqualTo("文件传输助手")
        assertThat(self.unreadBadge.visibility).isEqualTo(View.GONE)

        val mention = bind(listOf(item(unread = 1, mentionMe = true))).binding
        assertThat(mention.preview.text.toString()).isEqualTo("[有人@我] 你好")
    }

    @Test
    fun timeText() {
        assertThat(ConversationAdapter.timeText(context, TimeLabel.Today(9, 5))).isEqualTo("09:05")
        assertThat(ConversationAdapter.timeText(context, TimeLabel.Yesterday)).isEqualTo("昨天")
        assertThat(ConversationAdapter.timeText(context, TimeLabel.Weekday(7))).isEqualTo("星期日")
        assertThat(ConversationAdapter.timeText(context, TimeLabel.ThisYear(10, 6))).isEqualTo("10月6日")
        assertThat(ConversationAdapter.timeText(context, TimeLabel.Older(2025, 1, 2))).isEqualTo("2025/1/2")
    }
}
