package cn.android666.im.ui.chat

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.R
import cn.android666.im.data.message.ChatMessage
import cn.android666.im.data.message.MessageState
import cn.android666.im.databinding.ItemChatInBinding
import cn.android666.im.databinding.ItemChatNoticeBinding
import cn.android666.im.databinding.ItemChatOutBinding
import cn.android666.im.databinding.ItemChatTimeBinding
import cn.android666.im.ui.conversation.ConversationAdapter
import cn.android666.im.ui.conversation.TimeLabel

/**
 * 聊天页消息列表（开发计划 4.8）：时间分隔、对方的消息、本人的消息、系统提示（撤回）。
 * 非文本消息暂时显示降级文本（如"[图片]"），媒体消息的显示见 M5。
 *
 * @property showReadState 是否显示"未读 / 已读"（单聊显示；文件传输助手不显示；群聊的已读人数见 M6）
 * @property now 当前时间（毫秒），计算时间分隔的文字
 * @property onResend 点击发送失败的图标（重发）
 * @property onLongClick 长按消息气泡（操作菜单）
 */
class ChatAdapter(
    private val showReadState: Boolean,
    private val now: () -> Long,
    private val onResend: (ChatMessage) -> Unit,
    private val onLongClick: (ChatMessage) -> Unit = {},
) : PagingDataAdapter<ChatListItem, RecyclerView.ViewHolder>(DIFF) {

    /**
     * 时间分隔。
     *
     * @property binding 布局
     */
    class TimeHolder(val binding: ItemChatTimeBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 对方的消息。
     *
     * @property binding 布局
     */
    class InHolder(val binding: ItemChatInBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 本人的消息。
     *
     * @property binding 布局
     */
    class OutHolder(val binding: ItemChatOutBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 系统提示。
     *
     * @property binding 布局
     */
    class NoticeHolder(val binding: ItemChatNoticeBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 列表项的类型。
     *
     * @param position 位置
     * @return 类型
     */
    override fun getItemViewType(position: Int): Int = when (val item = peek(position)) {
        is ChatListItem.TimeHeader -> TYPE_TIME
        is ChatListItem.Message -> when {
            item.message.retracted -> TYPE_NOTICE
            item.message.outgoing -> TYPE_OUT
            else -> TYPE_IN
        }
        // 占位（未启用占位符时不会出现）
        null -> TYPE_NOTICE
    }

    /**
     * 创建列表项。
     *
     * @param parent 父容器
     * @param viewType 类型
     * @return 列表项
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TIME -> TimeHolder(ItemChatTimeBinding.inflate(inflater, parent, false))
            TYPE_IN -> InHolder(ItemChatInBinding.inflate(inflater, parent, false))
            TYPE_OUT -> OutHolder(ItemChatOutBinding.inflate(inflater, parent, false))
            else -> NoticeHolder(ItemChatNoticeBinding.inflate(inflater, parent, false))
        }
    }

    /**
     * 绑定数据。
     *
     * @param holder 列表项
     * @param position 位置
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        when (holder) {
            is TimeHolder -> holder.binding.text.text =
                timeHeaderText(holder.itemView.context, ChatTimeline.label((item as ChatListItem.TimeHeader).time, now()))
            is InHolder -> bindIn(holder.binding, (item as ChatListItem.Message).message)
            is OutHolder -> bindOut(holder.binding, (item as ChatListItem.Message).message)
            is NoticeHolder -> {
                val message = (item as? ChatListItem.Message)?.message ?: return
                holder.binding.text.setText(if (message.outgoing) R.string.chat_retracted_self else R.string.chat_retracted_peer)
            }
        }
    }

    /**
     * 绑定对方的消息。
     *
     * @param b 布局
     * @param message 消息
     */
    private fun bindIn(b: ItemChatInBinding, message: ChatMessage) {
        val name = message.senderName ?: message.senderJid.substringBefore('@')
        b.avatar.bind(name, message.senderJid, message.senderAvatarHash)
        b.bubble.text = message.body
        b.bubble.setOnLongClickListener {
            onLongClick(message)
            true
        }
    }

    /**
     * 绑定本人的消息：发送中显示进度，失败显示可点击的感叹号（重发）和原因，单聊显示"未读 / 已读"。
     *
     * @param b 布局
     * @param message 消息
     */
    private fun bindOut(b: ItemChatOutBinding, message: ChatMessage) {
        val name = message.senderName ?: message.senderJid.substringBefore('@')
        b.avatar.bind(name, message.senderJid, message.senderAvatarHash)
        b.bubble.text = message.body
        b.bubble.setOnLongClickListener {
            onLongClick(message)
            true
        }
        b.sending.isVisible = message.state == MessageState.SENDING
        b.failed.isVisible = message.state == MessageState.FAILED
        b.failed.setOnClickListener { onResend(message) }
        val readText = readStateText(message.state)
        b.readState.isVisible = showReadState && readText != null
        readText?.let(b.readState::setText)
        val error = errorText(message)
        b.error.isVisible = error != null
        error?.let(b.error::setText)
    }

    companion object {
        /** 时间分隔 */
        const val TYPE_TIME = 0

        /** 对方的消息 */
        const val TYPE_IN = 1

        /** 本人的消息 */
        const val TYPE_OUT = 2

        /** 系统提示 */
        const val TYPE_NOTICE = 3

        /** 按 key 判断是否同一项 */
        private val DIFF = object : DiffUtil.ItemCallback<ChatListItem>() {
            override fun areItemsTheSame(oldItem: ChatListItem, newItem: ChatListItem) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: ChatListItem, newItem: ChatListItem) = oldItem == newItem
        }

        /**
         * "未读 / 已读"文字。
         *
         * @param state 消息状态
         * @return 文字资源；发送中、失败时为 null
         */
        fun readStateText(state: MessageState): Int? = when (state) {
            MessageState.SENT, MessageState.DELIVERED -> R.string.chat_unread
            MessageState.READ -> R.string.chat_read
            else -> null
        }

        /**
         * 发送失败的原因提示：只对需要用户知道原因的错误显示（敏感词，协议规范 §6.4）。
         *
         * @param message 消息
         * @return 文字资源；不需要提示时为 null
         */
        fun errorText(message: ChatMessage): Int? =
            if (message.state == MessageState.FAILED && message.errorCode == "sensitive-word") R.string.chat_error_sensitive else null

        /**
         * 时间分隔的文字：今天"HH:mm"，其余"昨天 HH:mm""星期一 HH:mm""10月6日 HH:mm""2025年1月2日 HH:mm"。
         *
         * @param context Context
         * @param label 时间
         * @return 文字
         */
        fun timeHeaderText(context: Context, label: ChatTimeLabel): String {
            val day = when (val d = label.day) {
                is TimeLabel.Today, TimeLabel.None -> return context.getString(R.string.conversation_time_today, label.hour, label.minute)
                is TimeLabel.Older -> context.getString(R.string.chat_time_older_day, d.year, d.month, d.day)
                else -> ConversationAdapter.timeText(context, d)
            }
            return context.getString(R.string.chat_time_with_day, day, label.hour, label.minute)
        }
    }
}
