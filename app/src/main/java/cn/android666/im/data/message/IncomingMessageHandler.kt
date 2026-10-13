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
 * 当前处理：单聊的用户可见消息（去重 → 写库 → 更新会话 / 未读 → 回送达回执）。
 * 其余类型在对应的小功能中接入：回执与错误（4.5）、Carbons（4.6）、已读（4.9）、MAM（4.10，由补拉器自己收集）、
 * 撤回（4.12）、正在输入（4.14）、群聊（M6）。
 *
 * @property client XMPP 客户端
 * @property repository 消息仓库
 * @property serverClock 服务器时间（实时消息的时间）
 * @property ids ID 生成器（回执报文的 ID、缺少 ID 的消息）
 * @property selfJid 本人 bare JID；未登录时为 null
 * @property scope 应用级协程作用域
 */
class IncomingMessageHandler(
    private val client: XmppClient,
    private val repository: MessageRepository,
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
                log.debug("Carbons 抄送暂未处理（4.6）：sent={}，id={}", envelope.sent, msg.msgId)
                return
            }
            null -> Unit
        }
        when {
            msg.type == "groupchat" -> log.debug("群消息暂未处理（M6）：id={}", msg.msgId)
            msg.type == "error" -> log.debug("错误消息暂未处理（4.5）：id={}", msg.msgId)
            msg.content != null && (msg.type == "chat" || msg.type == "normal") -> onChatMessage(msg, self)
            else -> log.debug("暂未处理的消息：type={}，id={}", msg.type, msg.msgId)
        }
    }

    /**
     * 单聊的用户可见消息：保存，并在需要时回送达回执（写库之后，协议规范 §8.1）。
     *
     * @param msg 消息
     * @param self 本人 bare JID
     */
    private suspend fun onChatMessage(msg: MessageStanza, self: String) {
        val from = msg.from ?: run {
            log.warn("消息没有发送方，忽略：id={}", msg.msgId)
            return
        }
        val sender = IncomingMessageMapper.bareJid(from)
        if (sender == self) {
            log.debug("本人其他端发给本人的消息暂未处理（4.6 文件传输助手）：id={}", msg.msgId)
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
        )
        val saved = repository.saveIncoming(entity, ConversationEntity.TYPE_SINGLE)
        if (saved != null) {
            log.info("收到消息：msgId={}，来自={}，类型={}，离线={}", saved.msgId, sender, saved.type, msg.delayStamp != null)
            newMessageFlow.tryEmit(saved)
        } else {
            log.info("重复的消息，丢弃：msgId={}，来自={}", entity.msgId, sender)
        }
        // 重复的消息也回执：对方可能没收到上一次的回执（例如离线消息与重发同时到达）
        if (msg.requestReceipt && msg.msgId != null) sendReceipt(from, msg.msgId!!)
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
    }
}
