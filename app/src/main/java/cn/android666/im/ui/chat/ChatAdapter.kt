package cn.android666.im.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.AnimationDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.R
import cn.android666.im.core.media.ImageSizing
import cn.android666.im.core.xmpp.extension.msg.FileInfo
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.file.FileCategory
import cn.android666.im.data.file.FileTypes
import cn.android666.im.data.message.ChatMessage
import cn.android666.im.data.message.MediaStatus
import cn.android666.im.data.message.MessageState
import cn.android666.im.data.message.ReplyPreview
import cn.android666.im.data.message.SendError
import cn.android666.im.data.message.VoiceRules
import cn.android666.im.databinding.ItemChatInBinding
import cn.android666.im.databinding.ItemChatNoticeBinding
import cn.android666.im.databinding.ItemChatOutBinding
import cn.android666.im.databinding.ItemChatTimeBinding
import cn.android666.im.databinding.ViewChatFileBinding
import cn.android666.im.databinding.ViewChatImageBinding
import cn.android666.im.databinding.ViewChatVoiceBinding
import cn.android666.im.ui.conversation.ConversationAdapter
import cn.android666.im.ui.conversation.TimeLabel
import coil3.dispose
import coil3.load
import java.io.File

/**
 * 聊天页消息列表（开发计划 4.8）：时间分隔、对方的消息、本人的消息、系统提示（撤回）。
 * 图片消息显示缩略图（5.4）、语音消息显示语音气泡（5.5）、文件消息显示文件卡片（5.6）；其余非文本消息暂时显示降级文本（如"[视频]"）。
 *
 * @property showReadState 是否显示"未读 / 已读"（单聊显示；文件传输助手不显示；群聊的已读人数见 M6）
 * @property now 当前时间（毫秒），计算时间分隔的文字
 * @property onResend 点击发送失败的图标（重发）
 * @property onLongClick 长按消息气泡（操作菜单）
 * @property selfJid 本人 bare JID（引用本人的消息时显示"我"）
 * @property onToggle 多选模式中点击消息（切换选中）
 * @property onOpen 点击消息气泡（合并转发的聊天记录、图片、文件等需要打开的消息）
 * @property onVoice 点击语音消息（播放 / 停止）
 */
class ChatAdapter(
    private val showReadState: Boolean,
    private val now: () -> Long,
    private val onResend: (ChatMessage) -> Unit,
    private val onLongClick: (ChatMessage) -> Unit = {},
    private val selfJid: String? = null,
    private val onToggle: (ChatMessage) -> Unit = {},
    private val onOpen: (ChatMessage) -> Unit = {},
    private val onVoice: (ChatMessage) -> Unit = {},
) : PagingDataAdapter<ChatListItem, RecyclerView.ViewHolder>(DIFF) {

    /** 多选模式中已选中的消息 rowId；不在多选模式时为 null。修改后刷新全部列表项 */
    var selection: Set<Long>? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** 正在播放的语音 rowId（显示播放动画）；修改后刷新全部列表项 */
    var playingVoice: Long? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

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
        bindSelection(b.root, b.check, message)
        val name = message.senderName ?: message.senderJid.substringBefore('@')
        b.avatar.bind(name, message.senderJid, message.senderAvatarHash)
        b.bubble.text = message.body
        b.bubble.setOnLongClickListener {
            if (selection == null) onLongClick(message)
            selection == null
        }
        bindImage(b.imageBox, message)
        bindVoice(b.voiceBox, message, outgoing = false)
        bindFile(b.fileBox, message)
        b.bubble.isVisible = !showsMedia(message)
        bindQuote(b.quote, message)
    }

    /**
     * 绑定本人的消息：发送中显示进度，失败显示可点击的感叹号（重发）和原因，单聊显示"未读 / 已读"。
     *
     * @param b 布局
     * @param message 消息
     */
    private fun bindOut(b: ItemChatOutBinding, message: ChatMessage) {
        bindSelection(b.root, b.check, message)
        val name = message.senderName ?: message.senderJid.substringBefore('@')
        b.avatar.bind(name, message.senderJid, message.senderAvatarHash)
        b.bubble.text = message.body
        b.bubble.setOnLongClickListener {
            if (selection == null) onLongClick(message)
            selection == null
        }
        bindImage(b.imageBox, message)
        bindVoice(b.voiceBox, message, outgoing = true)
        bindFile(b.fileBox, message)
        b.bubble.isVisible = !showsMedia(message)
        bindQuote(b.quote, message)
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

    /**
     * 合并转发的气泡：点击查看聊天记录（多选模式中点击为切换选中）。
     *
     * @param root 列表项根布局
     * @param message 消息
     */
    private fun bindOpen(root: android.view.View, message: ChatMessage) {
        val bubble = root.findViewById<android.view.View>(R.id.bubble)
        if (message.content is cn.android666.im.core.xmpp.extension.msg.MessageContent.Merged) {
            bubble.setOnClickListener { if (selection == null) onOpen(message) else onToggle(message) }
        } else {
            bubble.setOnClickListener(null)
        }
    }

    /**
     * 多选模式：显示勾选框，点击整行切换选中；非多选模式隐藏勾选框。
     *
     * @param root 列表项根布局
     * @param check 勾选框
     * @param message 消息
     */
    private fun bindSelection(root: android.view.View, check: android.widget.CheckBox, message: ChatMessage) {
        bindOpen(root, message)
        val selected = selection
        check.isVisible = selected != null
        check.isChecked = selected?.contains(message.rowId) == true
        if (selected != null) {
            root.setOnClickListener { onToggle(message) }
        } else {
            root.setOnClickListener(null)
            root.isClickable = false
        }
    }

    /**
     * 图片消息（开发计划 5.4）：按图片宽高显示缩略图（文字气泡由调用方隐藏）；本人发出、上传中时显示进度，上传失败时提示。
     * 点击查看大图（多选模式中为切换选中），长按弹出操作菜单。
     *
     * @param box 图片布局
     * @param message 消息
     */
    private fun bindImage(box: ViewChatImageBinding, message: ChatMessage) {
        val image = message.content as? MessageContent.Image
        box.root.isVisible = image != null
        if (image == null) {
            box.image.dispose()
            box.image.setImageDrawable(null)
            return
        }
        val density = box.root.resources.displayMetrics.density
        val size = ImageSizing.displaySize(
            width = image.thumbnail?.width ?: image.file.width ?: 0,
            height = image.thumbnail?.height ?: image.file.height ?: 0,
            maxSide = (IMAGE_MAX_DP * density).toInt(),
            minSide = (IMAGE_MIN_DP * density).toInt(),
        )
        box.image.updateLayoutParams {
            width = size.width
            height = size.height
        }
        box.image.load(imageModel(message, image))
        val media = message.media
        box.progress.isVisible = media != null && (media.uploading || media.failed)
        when {
            media?.uploading == true -> box.progress.text = box.root.context.getString(R.string.chat_upload_progress, media.progress)
            media?.failed == true -> box.progress.setText(R.string.chat_upload_failed)
        }
        box.root.setOnClickListener { if (selection == null) onOpen(message) else onToggle(message) }
        box.root.setOnLongClickListener {
            if (selection == null) onLongClick(message)
            selection == null
        }
    }

    /**
     * 语音消息（开发计划 5.5）：气泡宽度随时长变化，显示秒数；对方发来的未听语音显示红点；正在播放时显示声波动画。
     * 本人发出的从右到左排列（声波在右侧、朝左）。点击播放 / 停止（多选模式中为切换选中），长按弹出操作菜单。
     *
     * @param box 语音布局
     * @param message 消息
     * @param outgoing 是否本人发出
     */
    private fun bindVoice(box: ViewChatVoiceBinding, message: ChatMessage, outgoing: Boolean) {
        val voice = message.content as? MessageContent.Voice
        box.root.isVisible = voice != null
        val playing = voice != null && message.rowId == playingVoice
        val wave = box.wave.drawable as? AnimationDrawable
        if (!playing) wave?.stop()
        if (voice == null) return
        val density = box.root.resources.displayMetrics.density
        val duration = voice.file.duration ?: 0L
        box.root.layoutDirection = if (outgoing) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
        box.bubble.setBackgroundResource(if (outgoing) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)
        box.bubble.updateLayoutParams {
            width = VoiceRules.bubbleWidth(duration, (VOICE_MIN_DP * density).toInt(), (VOICE_MAX_DP * density).toInt())
        }
        box.wave.scaleX = if (outgoing) -1f else 1f
        box.duration.text = box.root.context.getString(R.string.chat_voice_duration, VoiceRules.seconds(duration))
        box.unplayed.isVisible = !outgoing && !message.voicePlayed
        if (playing) {
            if (wave == null || box.wave.tag != TAG_ANIMATING) {
                box.wave.setImageResource(R.drawable.anim_voice_playing)
                box.wave.tag = TAG_ANIMATING
            }
            (box.wave.drawable as? AnimationDrawable)?.start()
        } else if (box.wave.tag != null) {
            box.wave.setImageResource(R.drawable.ic_voice_wave_3)
            box.wave.tag = null
        }
        box.bubble.setOnClickListener { if (selection == null) onVoice(message) else onToggle(message) }
        box.bubble.setOnLongClickListener {
            if (selection == null) onLongClick(message)
            selection == null
        }
    }

    /**
     * 文件消息（开发计划 5.6）：文件名、大小与状态（未下载 / 下载中 / 已暂停 / 已下载 / 已过期 / 上传中）、类型标签、进度条。
     * 点击打开或下载（多选模式中为切换选中），长按弹出操作菜单。
     *
     * @param box 文件布局
     * @param message 消息
     */
    private fun bindFile(box: ViewChatFileBinding, message: ChatMessage) {
        val file = (message.content as? MessageContent.File)?.file
        box.root.isVisible = file != null
        if (file == null) return
        val context = box.root.context
        box.name.text = file.name
        box.status.text = fileStatusText(context, message, file)
        val category = FileTypes.category(file.name, file.mime)
        box.badge.text = FileTypes.extension(file.name).take(BADGE_MAX_CHARS).ifEmpty { "?" }
        box.badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, badgeColor(category)))
        val status = message.media?.status
        val transferring = status == MediaStatus.UPLOADING || status == MediaStatus.DOWNLOADING
        box.progress.isVisible = transferring
        if (transferring) box.progress.setProgressCompat(message.media?.progress ?: 0, false)
        box.root.setOnClickListener { if (selection == null) onOpen(message) else onToggle(message) }
        box.root.setOnLongClickListener {
            if (selection == null) onLongClick(message)
            selection == null
        }
    }

    /**
     * 显示引用的原消息。
     *
     * @param view 引用预览
     * @param message 消息
     */
    private fun bindQuote(view: android.widget.TextView, message: ChatMessage) {
        val reply = message.reply
        view.isVisible = reply != null
        if (reply != null) view.text = quoteText(view.context, reply, selfJid)
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

        /** 语音气泡的最小宽度（dp，1 秒） */
        private const val VOICE_MIN_DP = 72

        /** 语音气泡的最大宽度（dp，60 秒） */
        private const val VOICE_MAX_DP = 200

        /** 声波图标正在显示动画的标记 */
        private const val TAG_ANIMATING = "animating"

        /**
         * 是否以媒体样式显示（隐藏文字气泡）。
         *
         * @param message 消息
         * @return 图片、语音为 true
         */
        fun showsMedia(message: ChatMessage): Boolean =
            message.content is MessageContent.Image || message.content is MessageContent.Voice || message.content is MessageContent.File

        /** 文件类型标签最多显示的字符数 */
        private const val BADGE_MAX_CHARS = 4

        /**
         * 文件消息的状态文字："1.2 MB · 未下载"、"1.2 MB · 下载中 40%"等；本人发出、本地有文件时只显示大小。
         *
         * @param context Context
         * @param message 消息
         * @param file 文件描述
         * @return 文字
         */
        fun fileStatusText(context: Context, message: ChatMessage, file: FileInfo): String {
            val size = FileTypes.formatSize(file.size)
            val media = message.media
            val progress = media?.progress ?: 0
            val local = media?.filePath?.let(::File)?.exists() == true
            val state = when (media?.status) {
                MediaStatus.UPLOADING -> context.getString(R.string.chat_file_uploading, progress)
                MediaStatus.DOWNLOADING -> context.getString(R.string.chat_file_downloading, progress)
                MediaStatus.EXPIRED -> context.getString(R.string.chat_file_expired)
                MediaStatus.FAILED -> context.getString(if (message.outgoing && file.url.isEmpty()) R.string.chat_file_upload_failed else R.string.chat_file_failed)
                MediaStatus.DONE -> if (!local) {
                    context.getString(R.string.chat_file_not_downloaded)
                } else if (message.outgoing) {
                    null
                } else {
                    context.getString(R.string.chat_file_downloaded)
                }
                else -> if (progress > 0) context.getString(R.string.chat_file_paused, progress) else context.getString(R.string.chat_file_not_downloaded)
            }
            return if (state == null) size else context.getString(R.string.chat_file_status, size, state)
        }

        /**
         * 文件类型标签的颜色。
         *
         * @param category 文件大类
         * @return 颜色资源
         */
        fun badgeColor(category: FileCategory): Int = when (category) {
            FileCategory.PDF -> R.color.file_pdf
            FileCategory.WORD -> R.color.file_word
            FileCategory.EXCEL -> R.color.file_excel
            FileCategory.POWERPOINT -> R.color.file_powerpoint
            FileCategory.ARCHIVE -> R.color.file_archive
            FileCategory.TEXT -> R.color.file_text
            FileCategory.IMAGE -> R.color.file_image
            FileCategory.AUDIO -> R.color.file_audio
            FileCategory.VIDEO -> R.color.file_video
            FileCategory.APK -> R.color.file_apk
            FileCategory.OTHER -> R.color.file_other
        }

        /** 图片消息的最大边长（dp） */
        private const val IMAGE_MAX_DP = 150

        /** 图片消息的最小边长（dp） */
        private const val IMAGE_MIN_DP = 60

        /**
         * 图片消息显示的内容：本地缩略图（本人发出的）→ 缩略图地址 → 原图地址 → 本地原图（还没上传完时）。
         *
         * @param message 消息
         * @param image 图片内容
         * @return Coil 的加载对象（File 或 URL）；都没有时为 null
         */
        fun imageModel(message: ChatMessage, image: MessageContent.Image): Any? {
            message.media?.thumbnailPath?.let(::File)?.takeIf { it.exists() }?.let { return it }
            image.thumbnail?.url?.takeIf { it.isNotEmpty() }?.let { return it }
            image.file.url.takeIf { it.isNotEmpty() }?.let { return it }
            return message.media?.filePath?.let(::File)?.takeIf { it.exists() }
        }

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
         * 发送失败的原因提示：只对需要用户知道原因的错误显示（敏感词，协议规范 §6.4；附件上传失败、太大、文件已被清理，开发计划 5.4）。
         *
         * @param message 消息
         * @return 文字资源；不需要提示时为 null
         */
        fun errorText(message: ChatMessage): Int? = if (message.state != MessageState.FAILED) {
            null
        } else {
            when (message.errorCode) {
                "sensitive-word" -> R.string.chat_error_sensitive
                SendError.UPLOAD_FAILED -> R.string.chat_error_upload
                SendError.FILE_TOO_LARGE -> R.string.chat_error_file_too_large
                SendError.FILE_MISSING -> R.string.chat_error_file_missing
                else -> null
            }
        }

        /**
         * 引用预览的文字："李四：原文"、本人的消息"我：原文"、"原消息已撤回"、"原消息不可见"。
         *
         * @param context Context
         * @param reply 原消息
         * @param selfJid 本人 bare JID
         * @return 文字
         */
        fun quoteText(context: Context, reply: ReplyPreview, selfJid: String?): String = when (reply) {
            ReplyPreview.Missing -> context.getString(R.string.chat_quote_missing)
            ReplyPreview.Retracted -> context.getString(R.string.chat_quote_retracted)
            is ReplyPreview.Found -> {
                val name = when {
                    reply.senderJid == selfJid -> context.getString(R.string.chat_quote_self)
                    else -> reply.senderName ?: reply.senderJid.substringBefore('@')
                }
                context.getString(R.string.chat_quote_format, name, reply.text)
            }
        }

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
