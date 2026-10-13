package cn.android666.im.ui.chat

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.core.media.MediaImporter
import cn.android666.im.data.file.FileTooLargeException
import cn.android666.im.data.message.MediaSender
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.data.conversation.toEntityType
import cn.android666.im.data.message.MessageRepository
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.message.ChatMessage
import cn.android666.im.data.message.ChatStates
import cn.android666.im.data.message.ReadMarkers
import cn.android666.im.data.message.RetractResult
import cn.android666.im.data.message.Retractions
import cn.android666.im.data.message.canRetract
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.data.sync.HistoryLoader
import cn.android666.im.ui.forward.ForwardTarget
import cn.android666.im.ui.widget.WatermarkView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** 聊天页的一次性事件 */
sealed interface ChatEvent {
    /** 消息已交给发送队列：清空输入框 */
    data object Sent : ChatEvent

    /**
     * 提示。
     *
     * @property message 提示文字
     */
    data class Toast(@param:StringRes val message: Int) : ChatEvent

    /**
     * 文件超过服务器的上传上限（开发计划 5.4）。
     *
     * @property maxBytes 上限（字节）
     */
    data class FileTooLarge(val maxBytes: Long) : ChatEvent
}

/**
 * 正在引用的消息（输入框上方的引用条，开发计划 4.15）。
 *
 * @property msgId 被引用消息的 ID
 * @property senderJid 被引用消息的发送方
 * @property senderName 发送方姓名；本人为 null（界面显示"我"），组织架构中没有时为工号
 * @property text 被引用消息的正文 / 降级文本
 */
data class ReplyDraft(val msgId: String, val senderJid: String, val senderName: String?, val text: String)

/**
 * 合并转发标题的格式（开发计划 4.16）。界面用字符串资源实现；默认实现用于测试。
 */
interface MergedTitleFormatter {

    /**
     * 两人的聊天记录。
     *
     * @param self 本人姓名
     * @param peer 对方姓名
     * @return 标题
     */
    fun pair(self: String, peer: String): String

    /**
     * 本人（文件传输助手）的聊天记录。
     *
     * @param self 本人姓名
     * @return 标题
     */
    fun selfOnly(self: String): String

    companion object {
        /** 默认格式（与字符串资源相同） */
        val DEFAULT = object : MergedTitleFormatter {
            override fun pair(self: String, peer: String) = "${self}和${peer}的聊天记录"
            override fun selfOnly(self: String) = "${self}的聊天记录"
        }
    }
}

/**
 * 聊天页（开发计划 4.8）：分页消息（带时间分隔）、发送、重发、当前查看的会话。
 *
 * @property conversationId 会话 ID（对方 bare JID / 群 JID / 本人 bare JID）
 * @property kind 会话类型
 * @property messages 消息仓库
 * @property conversations 会话仓库（清除未读）
 * @property readMarkers 已读标记（单聊打开时发送）
 * @property retractions 撤回
 * @property org 组织架构（标题显示对方姓名、引用条中的发送方姓名）
 * @param history 从服务器加载更早历史（开发计划 4.11）；null 表示只显示本地消息
 * @property chatStates 正在输入状态（开发计划 4.14，仅单聊）；null 时不发送也不显示
 * @property titleFormatter 合并转发标题的格式（字符串资源由界面提供）
 * @property media 媒体消息发送（开发计划 5.4）；null 时不能发图片，重发直接交给消息仓库
 * @property importer 把相册等返回的 Uri 复制到本地
 */
class ChatViewModel(
    val conversationId: String,
    val kind: ConversationKind,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val readMarkers: ReadMarkers,
    private val retractions: Retractions,
    private val org: OrgRepository,
    history: HistoryLoader? = null,
    private val chatStates: ChatStates? = null,
    private val titleFormatter: MergedTitleFormatter = MergedTitleFormatter.DEFAULT,
    private val media: MediaSender? = null,
    private val importer: MediaImporter? = null,
) : ViewModel() {

    /** 聊天水印的文字："姓名 工号"（开发计划 4.18）；未登录时为 null */
    val watermark: StateFlow<String?> = flow {
        val self = messages.selfJid()
        emit(self?.let { WatermarkView.label(org.employee(it)?.name, it.substringBefore('@')) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** 对方是否正在输入（单聊，标题栏下方显示"对方正在输入…"） */
    val peerTyping: StateFlow<Boolean> =
        (if (kind == ConversationKind.SINGLE && chatStates != null) chatStates.observePeerTyping(conversationId) else flowOf(false))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    private val replyDraft = MutableStateFlow<ReplyDraft?>(null)

    /** 正在引用的消息；没有时为 null */
    val reply: StateFlow<ReplyDraft?> = replyDraft.asStateFlow()

    /**
     * 引用一条消息（长按菜单"引用"）。
     *
     * @param rowId 被引用消息的 rowId
     */
    fun startReply(rowId: Long) {
        viewModelScope.launch {
            try {
                val message = messages.chatMessage(rowId) ?: return@launch
                if (message.retracted) return@launch
                val name = if (message.outgoing) null else org.employee(message.senderJid)?.name ?: message.senderJid.substringBefore('@')
                replyDraft.value = ReplyDraft(message.msgId, message.senderJid, name, message.body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("引用失败：rowId={}", rowId, e)
            }
        }
    }

    private val selectionState = MutableStateFlow<Set<Long>?>(null)

    /** 多选模式中已选中的消息 rowId；不在多选模式时为 null（开发计划 4.17） */
    val selection: StateFlow<Set<Long>?> = selectionState.asStateFlow()

    /** 等待确认删除的消息 rowId（确认框在页面重建后仍在，ID 保存在 ViewModel 中） */
    var pendingDelete: Set<Long>? = null

    /**
     * 进入多选模式并选中一条消息。
     *
     * @param rowId 消息 rowId
     */
    fun startSelection(rowId: Long) {
        selectionState.value = setOf(rowId)
    }

    /**
     * 多选模式中切换一条消息的选中状态；最多选 100 条。
     *
     * @param rowId 消息 rowId
     */
    fun toggleSelection(rowId: Long) {
        val current = selectionState.value ?: return
        selectionState.value = when {
            rowId in current -> current - rowId
            current.size >= MessageRepository.MAX_SELECTION -> {
                eventChannel.trySend(ChatEvent.Toast(R.string.chat_selection_limit))
                current
            }
            else -> current + rowId
        }
    }

    /** 退出多选模式 */
    fun cancelSelection() {
        selectionState.value = null
    }

    /**
     * 删除本地消息（多选的、或长按菜单中的一条），完成后退出多选模式。
     *
     * @param rowIds 消息 rowId
     */
    fun delete(rowIds: Set<Long>) {
        viewModelScope.launch {
            try {
                messages.deleteLocal(rowIds)
                selectionState.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("删除消息失败", e)
            }
        }
    }

    /** 等待选择转发对象的消息（rowId 与是否合并）；选择页返回后使用 */
    var pendingForward: Pair<Set<Long>, Boolean>? = null

    /**
     * 转发到选定的目标（开发计划 4.16）；完成后退出多选模式并提示。
     *
     * @param target 目标
     * @param rowIds 被转发的消息
     * @param merged 是否合并转发
     */
    fun forward(target: ForwardTarget, rowIds: Set<Long>, merged: Boolean) {
        viewModelScope.launch {
            try {
                val title = if (merged) mergedTitle() else null
                val sent = messages.forward(target.id, target.kind.toEntityType(), rowIds, title)
                selectionState.value = null
                if (sent > 0) eventChannel.send(ChatEvent.Toast(R.string.forward_done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("转发失败：目标={}", target.id, e)
                eventChannel.send(ChatEvent.Toast(R.string.chat_send_error))
            }
        }
    }

    /**
     * 合并转发的标题：单聊为"张三和李四的聊天记录"，文件传输助手为"张三的聊天记录"。
     *
     * @return 标题
     */
    private suspend fun mergedTitle(): String {
        val self = messages.selfJid()
        val selfName = self?.let { org.employee(it)?.name ?: it.substringBefore('@') }.orEmpty()
        return if (kind == ConversationKind.SELF || self == conversationId) {
            titleFormatter.selfOnly(selfName)
        } else {
            val peer = org.employee(conversationId)?.name ?: conversationId.substringBefore('@')
            titleFormatter.pair(selfName, peer)
        }
    }

    /**
     * 读取草稿（打开聊天页时恢复到输入框，开发计划 4.17）。
     *
     * @return 草稿；没有时为空字符串
     */
    suspend fun loadDraft(): String = try {
        conversations.draft(conversationId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("读取草稿失败：{}", conversationId, e)
        ""
    }

    /**
     * 保存草稿（离开聊天页时）。离开页面后 ViewModel 可能随即销毁，保存不能随之取消，所以使用 [NonCancellable]。
     *
     * @param text 输入框内容
     */
    fun saveDraft(text: String) {
        viewModelScope.launch(NonCancellable) {
            try {
                conversations.saveDraft(conversationId, kind, text)
            } catch (e: Exception) {
                log.warn("保存草稿失败：{}", conversationId, e)
            }
        }
    }

    /** 取消引用 */
    fun cancelReply() {
        replyDraft.value = null
    }

    /**
     * 输入框内容变化：单聊发送正在输入状态（节流），清空时停止。
     *
     * @param text 输入框内容
     */
    fun onInputChanged(text: String) {
        val states = chatStates?.takeIf { kind == ConversationKind.SINGLE } ?: return
        if (text.isEmpty()) states.onStopTyping(conversationId) else states.onTyping(conversationId)
    }

    private val log = Logs.file(TAG)

    /** 分页消息，相邻消息间隔超过 5 分钟时插入时间分隔；本地翻到底后从服务器加载更早的历史 */
    @OptIn(ExperimentalPagingApi::class)
    val items: Flow<PagingData<ChatListItem>> = messages.pagedMessages(conversationId, history?.mediator(conversationId))
        .map { page ->
            page.map<_, ChatListItem> { ChatListItem.Message(it) }
                .insertSeparators { newer, older ->
                    ChatTimeline.separator((newer as? ChatListItem.Message)?.message, (older as? ChatListItem.Message)?.message)
                }
        }
        .cachedIn(viewModelScope)

    /** 对方姓名（单聊，组织架构中有时）；null 时界面显示工号或固定名称 */
    val peerName: StateFlow<String?> =
        (if (kind == ConversationKind.SINGLE) org.observeEmployee(conversationId).map { it?.name } else flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val eventChannel = Channel<ChatEvent>(Channel.BUFFERED)

    /** 一次性事件 */
    val events: Flow<ChatEvent> = eventChannel.receiveAsFlow()

    init {
        // 撤回被服务器拒绝（超时、无权限）：提示
        viewModelScope.launch {
            retractions.rejections.collect { code ->
                eventChannel.send(ChatEvent.Toast(if (code == "retract-forbidden") R.string.chat_retract_forbidden else R.string.chat_retract_too_late))
            }
        }
    }

    /**
     * 长按消息时可用的操作：文字消息可复制；未撤回的消息可引用、转发；都可以多选、删除（本地）；本人 2 分钟内发出的消息可撤回。
     *
     * @param message 消息
     * @param now 当前服务器时间（毫秒）
     * @return 操作（MessageActionsDialogFragment.ACTION_*）
     */
    fun actionsFor(message: ChatMessage, now: Long = messages.serverNowMillis()): List<String> = buildList {
        if (!message.retracted && message.content is MessageContent.Text) add(MessageActionsDialogFragment.ACTION_COPY)
        if (!message.retracted) add(MessageActionsDialogFragment.ACTION_REPLY)
        if (!message.retracted) add(MessageActionsDialogFragment.ACTION_FORWARD)
        add(MessageActionsDialogFragment.ACTION_SELECT)
        if (message.canRetract(now)) add(MessageActionsDialogFragment.ACTION_RETRACT)
        add(MessageActionsDialogFragment.ACTION_DELETE)
    }

    /**
     * 撤回一条本人发出的消息。
     *
     * @param rowId 消息 rowId
     */
    fun retract(rowId: Long) {
        viewModelScope.launch {
            val result = try {
                retractions.retract(rowId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("撤回出错：rowId={}", rowId, e)
                RetractResult.Failed
            }
            log.info("撤回：rowId={}，结果={}", rowId, result)
            when (result) {
                RetractResult.Success, RetractResult.NotAllowed -> Unit
                RetractResult.TooLate -> eventChannel.send(ChatEvent.Toast(R.string.chat_retract_too_late))
                RetractResult.Failed -> eventChannel.send(ChatEvent.Toast(R.string.chat_retract_failed))
            }
        }
    }

    /**
     * 发送文本。空白不发送；超过 5000 字提示。
     *
     * @param text 输入框内容
     */
    fun send(text: String) {
        if (text.isBlank()) return
        if (text.codePointCount(0, text.length) > MessageRepository.MAX_TEXT_LENGTH) {
            eventChannel.trySend(ChatEvent.Toast(R.string.chat_text_too_long))
            return
        }
        viewModelScope.launch {
            try {
                messages.sendText(conversationId, kind.toEntityType(), text, replyToMsgId = replyDraft.value?.msgId)
                replyDraft.value = null
                if (kind == ConversationKind.SINGLE) chatStates?.onStopTyping(conversationId)
                eventChannel.send(ChatEvent.Sent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("发送失败：会话={}", conversationId, e)
                eventChannel.send(ChatEvent.Toast(R.string.chat_send_error))
            }
        }
    }

    /**
     * 发送相册中选择的图片（开发计划 5.4）：按选择顺序逐张导入、压缩并发送；某张失败时提示并继续发送其余的。
     *
     * @param uris 图片地址
     * @param original 是否发送原图
     */
    fun sendImages(uris: List<Uri>, original: Boolean) {
        val sender = media ?: return
        val imports = importer ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            log.info("发送图片：{} 张，原图={}，会话={}", uris.size, original, conversationId)
            for (uri in uris) {
                val imported = try {
                    imports.import(uri)
                } catch (e: IOException) {
                    log.warn("图片导入失败：{}", e.toString())
                    eventChannel.send(ChatEvent.Toast(R.string.chat_image_invalid))
                    continue
                }
                try {
                    sendImageFile(sender, imported.file, imported.displayName, original)
                } finally {
                    imported.file.delete()
                }
            }
        }
    }

    /**
     * 发送拍摄的照片（压缩发送，不发原图）。
     *
     * @param file 照片文件（发送后删除）
     * @param displayName 文件名
     */
    fun sendPhoto(file: File, displayName: String) {
        val sender = media ?: return
        viewModelScope.launch {
            try {
                sendImageFile(sender, file, displayName, original = false)
            } finally {
                file.delete()
            }
        }
    }

    /**
     * 发送一张本地图片，出错时提示。
     *
     * @param sender 媒体消息发送
     * @param file 图片
     * @param displayName 原始文件名
     * @param original 是否原图
     */
    private suspend fun sendImageFile(sender: MediaSender, file: File, displayName: String?, original: Boolean) {
        try {
            // 不发 ChatEvent.Sent：输入框中可能还有没发的文字
            sender.sendImage(conversationId, kind.toEntityType(), file, displayName, original)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileTooLargeException) {
            eventChannel.send(ChatEvent.FileTooLarge(e.maxSize))
        } catch (e: IOException) {
            log.warn("图片无法读取：{}", e.toString())
            eventChannel.send(ChatEvent.Toast(R.string.chat_image_invalid))
        } catch (e: Exception) {
            log.error("图片发送出错：会话={}", conversationId, e)
            eventChannel.send(ChatEvent.Toast(R.string.chat_send_error))
        }
    }

    /**
     * 重发一条发送失败的消息。
     *
     * @param rowId 消息 rowId
     */
    fun resend(rowId: Long) {
        viewModelScope.launch {
            try {
                // 媒体消息可能是附件没传完，由 MediaSender 判断重传附件还是只重发消息
                if (media != null) media.resend(rowId) else messages.resend(rowId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("重发失败：rowId={}", rowId, e)
            }
        }
    }

    /** 页面可见：该会话的新消息不计未读，清除已有未读；单聊同时向对方发送已读标记（开发计划 4.9） */
    fun onVisible() {
        messages.setActiveConversation(conversationId)
        viewModelScope.launch {
            try {
                if (kind == ConversationKind.SINGLE) readMarkers.markConversationRead(conversationId) else conversations.clearUnread(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("清除未读失败：{}", conversationId, e)
            }
        }
    }

    /** 页面不可见 */
    fun onHidden() {
        if (kind == ConversationKind.SINGLE) chatStates?.onStopTyping(conversationId)
        if (messages.activeConversationId() == conversationId) messages.setActiveConversation(null)
    }

    private companion object {
        const val TAG = "ChatViewModel"

        /** 页面不可见超过该时间后停止查询 */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
