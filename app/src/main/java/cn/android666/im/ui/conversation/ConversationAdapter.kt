package cn.android666.im.ui.conversation

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.text.inSpans
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.R
import cn.android666.im.data.conversation.ConversationItem
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.LastMessageState
import cn.android666.im.databinding.ItemConversationBinding
import com.google.android.material.color.MaterialColors

/**
 * 会话列表适配器。
 *
 * @property now 当前时间（毫秒），计算"今天 / 昨天"等时间标签
 * @property onClick 点击会话
 * @property onLongClick 长按会话（操作菜单），参数为会话和显示的名称
 */
class ConversationAdapter(
    private val now: () -> Long,
    private val onClick: (ConversationItem) -> Unit,
    private val onLongClick: (ConversationItem, String) -> Unit,
) : ListAdapter<ConversationItem, ConversationAdapter.Holder>(DIFF) {

    /**
     * 列表项。
     *
     * @property binding 布局
     */
    class Holder(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 创建列表项。
     *
     * @param parent 父容器
     * @param viewType 类型（只有一种）
     * @return 列表项
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    /**
     * 绑定数据。
     *
     * @param holder 列表项
     * @param position 位置
     */
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = b.root.context
        val name = displayName(context, item)
        b.avatar.bind(name, item.id, item.avatarHash)
        b.name.text = name
        b.time.text = timeText(context, ConversationDisplay.timeLabel(item.time, now()))

        val parts = ConversationDisplay.preview(item)
        b.preview.text = previewText(context, parts)
        val stateIcon = when {
            !parts.showState -> null
            item.lastMessageState == LastMessageState.FAILED -> R.drawable.ic_msg_failed
            item.lastMessageState == LastMessageState.SENDING -> R.drawable.ic_msg_sending
            else -> null
        }
        b.stateIcon.isVisible = stateIcon != null
        stateIcon?.let(b.stateIcon::setImageResource)
        b.mutedIcon.isVisible = item.muted

        // 免打扰的会话只显示小红点，不显示数字
        val badge = ConversationDisplay.badgeText(item.unreadCount)
        b.unreadBadge.isVisible = badge != null && !item.muted
        b.unreadBadge.text = badge
        b.unreadDot.isVisible = badge != null && item.muted

        b.root.setOnClickListener { onClick(item) }
        b.root.setOnLongClickListener {
            onLongClick(item, name)
            true
        }
    }

    companion object {
        /** 按会话 ID 判断是否同一项 */
        private val DIFF = object : DiffUtil.ItemCallback<ConversationItem>() {
            override fun areItemsTheSame(oldItem: ConversationItem, newItem: ConversationItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ConversationItem, newItem: ConversationItem) = oldItem == newItem
        }

        /**
         * 会话显示的名称。
         *
         * @param context Context
         * @param item 会话
         * @return 名称
         */
        fun displayName(context: Context, item: ConversationItem): String = ConversationDisplay.plainName(item)
            ?: context.getString(if (item.kind == ConversationKind.SELF) R.string.conversation_self else R.string.conversation_notice)

        /**
         * 时间标签 → 文字。
         *
         * @param context Context
         * @param label 时间标签
         * @return 文字
         */
        fun timeText(context: Context, label: TimeLabel): String = when (label) {
            TimeLabel.None -> ""
            is TimeLabel.Today -> context.getString(R.string.conversation_time_today, label.hour, label.minute)
            TimeLabel.Yesterday -> context.getString(R.string.conversation_yesterday)
            is TimeLabel.Weekday -> context.resources.getStringArray(R.array.conversation_weekdays)[label.dayOfWeek - 1]
            is TimeLabel.ThisYear -> context.getString(R.string.conversation_time_this_year, label.month, label.day)
            is TimeLabel.Older -> context.getString(R.string.conversation_time_older, label.year, label.month, label.day)
        }

        /**
         * 预览文字：前缀（[有人@我] / [草稿]）用错误色突出显示。
         *
         * @param context Context
         * @param parts 预览的组成
         * @return 文字
         */
        fun previewText(context: Context, parts: PreviewParts): CharSequence {
            val prefix = when (parts.prefix) {
                PreviewPrefix.MENTION -> R.string.conversation_prefix_mention
                PreviewPrefix.DRAFT -> R.string.conversation_prefix_draft
                null -> return parts.text
            }
            val color = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, 0)
            return SpannableStringBuilder()
                .inSpans(ForegroundColorSpan(color)) { append(context.getString(prefix)) }
                .append(' ')
                .append(parts.text)
        }
    }
}
