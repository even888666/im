package cn.android666.im.ui.chat

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
) : ViewModel() {

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
     * 长按消息时可用的操作：文字消息可复制；未撤回的消息可引用；本人 2 分钟内发出的消息可撤回。
     *
     * @param message 消息
     * @param now 当前服务器时间（毫秒）
     * @return 操作（MessageActionsDialogFragment.ACTION_*）
     */
    fun actionsFor(message: ChatMessage, now: Long = messages.serverNowMillis()): List<String> = buildList {
        if (!message.retracted && message.content is MessageContent.Text) add(MessageActionsDialogFragment.ACTION_COPY)
        if (!message.retracted) add(MessageActionsDialogFragment.ACTION_REPLY)
        if (message.canRetract(now)) add(MessageActionsDialogFragment.ACTION_RETRACT)
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
     * 重发一条发送失败的消息。
     *
     * @param rowId 消息 rowId
     */
    fun resend(rowId: Long) {
        viewModelScope.launch {
            try {
                messages.resend(rowId)
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
