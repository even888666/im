package cn.android666.im.data.message

import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.msg.IncomingMessage
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageEnvelope
import cn.android666.im.core.xmpp.extension.msg.MessageStanza
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 收到的消息的分发（开发计划 4.4，Android工程架构.md §4.5、§6.1）：订阅 [XmppClient.incomingMessages]，
 * 按到达顺序逐条处理。
 *
 * 当前处理：单聊的用户可见消息（去重 → 写库 → 更新会话 / 未读 → 回送达回执）、送达回执与服务器退回（4.5）、
 * Carbons 多端同步与文件传输助手（4.6）、已读标记（4.9）。
 * MAM 补拉的消息由 MamSyncer 收集后调用 [applyArchived]（4.10）。
 * 其余类型在对应的小功能中接入：
 * 撤回（4.12）、正在输入（4.14）、群聊（M6）。
 *
 * @property client XMPP 客户端
 * @property repository 消息仓库
 * @property readMarkers 已读标记（正在查看的会话收到新消息时发送已读；其他端已读的同步）
 * @property serverClock 服务器时间（实时消息的时间）
 * @property ids ID 生成器（回执报文的 ID、缺少 ID 的消息）
 * @property selfJid 本人 bare JID；未登录时为 null
 * @property scope 应用级协程作用域
 */
class IncomingMessageHandler(
    private val client: XmppClient,
    private val repository: MessageRepository,
    private val readMarkers: ReadMarkers,
    private val serverClock: ServerClock,
    private val ids: IdGenerator,
    private val selfJid: () -> String?,
    private val scope: CoroutineScope,
) {

    private val log = Logs.file(TAG)

    private val newMessageFlow = MutableSharedFlow<MessageEntity>(extraBufferCapacity = NEW_MESSAGE_BUFFER)

    /** 新保存的收到的消息（不含重复），消息通知（4.13）订阅 */
    val newMessages: SharedFlow<MessageEntity> = newMessageFlow.asSharedFlow()

    init {
        // 立即订阅：登录后服务器马上会投递离线消息
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.incomingMessages.collect { handle(it) }
        }
    }

    /**
     * 不同来源的消息的处理方式。
     *
     * @property reply 是否由本端回送达回执
     * @property notify 是否发出新消息通知
     * @property countUnread 是否计入未读
     * @property archiveId MAM 存档 ID（补拉的消息）
     */
    private data class Handling(val reply: Boolean, val notify: Boolean, val countUnread: Boolean, val archiveId: String? = null)

    /**
     * 处理一条 MAM 补拉到的消息（开发计划 4.10，由 MamSyncer 按时间顺序调用）：保存消息、应用已读，
     * 不回送达回执（协议规范 §8.1），不发通知；首次登录导入的历史消息不计未读。
     *
     * @param incoming 带 MAM 包装的报文
     * @param initial 是否首次登录导入历史
     */
    suspend fun applyArchived(incoming: IncomingMessage, initial: Boolean) {
        val envelope = incoming.envelope as? MessageEnvelope.Mam ?: return
        val self = selfJid() ?: return
        val msg = incoming.message
        val from = msg.from ?: return
        val sender = IncomingMessageMapper.bareJid(from)
        val handling = Handling(reply = false, notify = false, countUnread = !initial, archiveId = envelope.stanzaId)
        when {
            msg.type == "groupchat" -> log.debug("群消息暂未处理（M6）：id={}", msg.msgId)
            msg.displayedId != null && sender == self -> {
                val peer = msg.to?.let(IncomingMessageMapper::bareJid) ?: return
                repository.applyReadElsewhere(peer, msg.displayedId)
                readMarkers.onMarkedElsewhere(peer, msg.displayedId)
            }
            msg.displayedId != null -> onDisplayed(msg, msg.displayedId)
            msg.content != null && isChatType(msg) && sender == self -> {
                val peer = msg.to?.let(IncomingMessageMapper::bareJid) ?: return
                saveOwn(msg, peer, self, "补拉", envelope.stanzaId)
            }
            msg.content != null && isChatType(msg) -> onChatMessage(msg, self, handling)
            else -> log.debug("补拉到暂未处理的消息：type={}，id={}", msg.type, msg.msgId)
        }
    }

    /**
     * 处理一条报文。任何异常都只记录日志，不影响后续报文。
     *
     * @param xml message 报文
     */
    internal suspend fun handle(xml: String) {
        val incoming = try {
            MessageCodec.parse(xml)
        } catch (e: IllegalArgumentException) {
            log.warn("收到无法解析的 message，忽略：{}", e.message)
            return
        }
        try {
            dispatch(incoming)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("处理收到的消息出错：id={}", incoming.message.msgId, e)
        }
    }

    /**
     * 按报文类型分发。
     *
     * @param incoming 解析后的报文
     */
    private suspend fun dispatch(incoming: IncomingMessage) {
        val self = selfJid() ?: run {
            log.warn("未登录时收到消息，忽略：id={}", incoming.message.msgId)
            return
        }
        val msg = incoming.message
        when (val envelope = incoming.envelope) {
            // MAM 结果由补拉器按 queryid 收集（4.10）
            is MessageEnvelope.Mam -> return
            is MessageEnvelope.Carbon -> {
                onCarbon(envelope, msg, self)
                return
            }
            null -> Unit
        }
        when {
            msg.type == "error" -> onError(msg)
            msg.type == "groupchat" -> log.debug("群消息暂未处理（M6）：id={}", msg.msgId)
            msg.receivedId != null -> onReceipt(msg, msg.receivedId)
            msg.displayedId != null -> onDisplayed(msg, msg.displayedId)
            msg.content != null && isChatType(msg) -> onChatMessage(msg, self)
            else -> log.debug("暂未处理的消息：type={}，id={}", msg.type, msg.msgId)
        }
    }

    /**
     * 送达回执（协议规范 §8.1）：发出的消息改为"已送达"。
     *
     * @param msg 回执报文
     * @param receivedId 被确认的消息 ID
     */
    private suspend fun onReceipt(msg: MessageStanza, receivedId: String) {
        val from = msg.from ?: return
        val conversationId = IncomingMessageMapper.bareJid(from)
        val updated = repository.markDelivered(conversationId, receivedId)
        log.info("送达回执：msgId={}，来自={}，更新={}", receivedId, conversationId, updated)
    }

    /**
     * 对方的已读标记（协议规范 §8.2）：被标记的消息及之前本人发出的消息都改为"已读"。
     *
     * @param msg 已读报文
     * @param displayedId 被标记已读的消息 ID
     */
    private suspend fun onDisplayed(msg: MessageStanza, displayedId: String) {
        val from = msg.from ?: return
        val conversationId = IncomingMessageMapper.bareJid(from)
        val updated = repository.markReadByPeer(conversationId, displayedId)
        log.info("对方已读：msgId={}，会话={}，更新 {} 条", displayedId, conversationId, updated)
    }

    /**
     * 服务器退回的错误（协议规范 §6.2、§6.4）：对应的发出消息标记失败。错误报文的 id 是原报文的 id，
     * 对回执、已读等非消息报文的退回找不到对应消息，直接忽略。
     *
     * @param msg 错误报文
     */
    private suspend fun onError(msg: MessageStanza) {
        val id = msg.msgId ?: return
        val from = msg.from ?: return
        val error = msg.error
        val code = error?.appErrorCode ?: error?.condition ?: "undefined-condition"
        val updated = repository.markRejected(IncomingMessageMapper.bareJid(from), id, code)
        log.warn("消息被退回：id={}，来自={}，错误={}，更新={}", id, from, code, updated)
    }

    /**
     * Carbons 抄送（开发计划 4.6，协议规范 §11.1）：本人其他端发出（sent）或收到（received）的单聊报文。
     * 外层 from 必须是本人 bare JID，否则可能是他人伪造的抄送（XEP-0280 §11），直接丢弃。
     *
     * @param envelope 抄送包装
     * @param msg 内层报文
     * @param self 本人 bare JID
     */
    private suspend fun onCarbon(envelope: MessageEnvelope.Carbon, msg: MessageStanza, self: String) {
        if (envelope.outerFrom != self) {
            log.warn("Carbons 的发送方不是本人，可能是伪造的，丢弃：outerFrom={}，id={}", envelope.outerFrom, msg.msgId)
            return
        }
        if (envelope.sent) {
            // 本人其他端发出：用户可见的消息保存为本人发出的；回执、正在输入等不处理（已读同步见 4.9）
            val to = msg.to ?: return
            val peer = IncomingMessageMapper.bareJid(to)
            if (msg.content != null && isChatType(msg)) {
                saveOwn(msg, peer, self, "抄送")
            } else if (msg.displayedId != null) {
                // 本人在其他端看过了这个会话：按剩余的未读消息更新未读数（多端已读同步，协议规范 §8.2）
                val remaining = repository.applyReadElsewhere(peer, msg.displayedId)
                readMarkers.onMarkedElsewhere(peer, msg.displayedId)
                log.info("其他端已读：会话={}，msgId={}，剩余未读={}", peer, msg.displayedId, remaining)
            } else {
                log.debug("其他端发出的非消息报文：id={}", msg.msgId)
            }
            return
        }
        // 本人其他端收到：与本端收到的处理相同，但回执由收到的那一端负责
        when {
            msg.receivedId != null -> onReceipt(msg, msg.receivedId)
            msg.displayedId != null -> onDisplayed(msg, msg.displayedId)
            msg.content != null && isChatType(msg) -> onChatMessage(msg, self, CARBON_RECEIVED)
            else -> log.debug("其他端收到的非消息报文：id={}", msg.msgId)
        }
    }

    /**
     * 本人发出的消息从其他端同步过来（Carbons 抄送，或其他端发给"文件传输助手"即本人 bare JID 的消息）：
     * 保存为"已发送"，不计未读。本端自己发出的会按消息 ID 去重。
     *
     * @param msg 消息
     * @param peer 对方 bare JID（文件传输助手为本人）
     * @param self 本人 bare JID
     * @param source 来源，写日志用
     * @param archiveId MAM 存档 ID（补拉的消息）
     */
    private suspend fun saveOwn(msg: MessageStanza, peer: String, self: String, source: String, archiveId: String? = null) {
        val entity = IncomingMessageMapper.toEntity(
            stanza = msg,
            conversationId = peer,
            senderJid = self,
            direction = MessageEntity.DIRECTION_OUT,
            status = MessageEntity.STATUS_SENT,
            serverTime = msg.delayStamp ?: serverClock.nowMillis(),
            selfJid = self,
            fallbackId = ids::messageId,
            archiveId = archiveId,
        )
        val type = if (peer == self) ConversationEntity.TYPE_SELF else ConversationEntity.TYPE_SINGLE
        val saved = repository.saveIncoming(entity, type)
        log.info("其他端发出的消息（{}）：msgId={}，会话={}，新消息={}", source, entity.msgId, peer, saved != null)
    }

    /**
     * 是否为单聊类型（chat，或未指定类型的 normal）。
     *
     * @param msg 消息
     * @return 是则为 true
     */
    private fun isChatType(msg: MessageStanza): Boolean = msg.type == "chat" || msg.type == "normal"

    /**
     * 单聊的用户可见消息：保存，并在需要时回送达回执（写库之后，协议规范 §8.1）。
     *
     * @param msg 消息
     * @param self 本人 bare JID
     * @param handling 处理方式（Carbons 抄送过来的由收到的那一端回执；补拉的不回执、不通知）
     */
    private suspend fun onChatMessage(msg: MessageStanza, self: String, handling: Handling = REALTIME) {
        val from = msg.from ?: run {
            log.warn("消息没有发送方，忽略：id={}", msg.msgId)
            return
        }
        val sender = IncomingMessageMapper.bareJid(from)
        if (sender == self) {
            // 文件传输助手：本人其他端发给本人 bare JID 的消息（协议规范 §11.1）
            saveOwn(msg, self, self, "文件传输助手", handling.archiveId)
            return
        }
        val entity = IncomingMessageMapper.toEntity(
            stanza = msg,
            conversationId = sender,
            senderJid = sender,
            direction = MessageEntity.DIRECTION_IN,
            status = MessageEntity.STATUS_RECEIVED,
            serverTime = msg.delayStamp ?: serverClock.nowMillis(),
            selfJid = self,
            fallbackId = ids::messageId,
            archiveId = handling.archiveId,
        )
        val saved = repository.saveIncoming(entity, ConversationEntity.TYPE_SINGLE, handling.countUnread)
        if (saved != null) {
            log.info("收到消息：msgId={}，来自={}，类型={}，离线={}，补拉={}", saved.msgId, sender, saved.type, msg.delayStamp != null, handling.archiveId != null)
            if (handling.notify) newMessageFlow.tryEmit(saved)
            // 用户正在看这个会话：立即标记已读（协议规范 §8.2）
            if (repository.activeConversationId() == sender) scope.launch { readMarkers.markConversationRead(sender) }
        } else {
            log.info("重复的消息，丢弃：msgId={}，来自={}", entity.msgId, sender)
        }
        // 重复的消息也回执：对方可能没收到上一次的回执（例如离线消息与重发同时到达）
        if (handling.reply && msg.requestReceipt && msg.msgId != null) sendReceipt(from, msg.msgId!!)
    }

    /**
     * 回送达回执。在独立协程中发送，不阻塞后续消息的处理；失败只记录日志（对方显示"已发送"而不是"已送达"）。
     *
     * @param to 原消息的发送方（完整 JID）
     * @param msgId 原消息 ID
     */
    private fun sendReceipt(to: String, msgId: String) {
        scope.launch {
            try {
                client.sendMessage(MessageCodec.receipt(to, msgId, ids.messageId()), RECEIPT_ACK_TIMEOUT_MILLIS)
            } catch (e: XmppException) {
                log.warn("送达回执发送失败：msgId={}，{}", msgId, e.message)
            }
        }
    }

    private companion object {
        const val TAG = "IncomingMessage"

        /** 新消息通知的缓冲 */
        const val NEW_MESSAGE_BUFFER = 64

        /** 回执等待服务器确认的超时 */
        const val RECEIPT_ACK_TIMEOUT_MILLIS = 30_000L

        /** 实时收到的消息：回执、通知、计未读 */
        val REALTIME = Handling(reply = true, notify = true, countUnread = true)

        /** 本人其他端收到的消息（抄送）：不回执（由收到的那一端回执），通知、计未读 */
        val CARBON_RECEIVED = Handling(reply = false, notify = true, countUnread = true)
    }
}
