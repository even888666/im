package cn.android666.im.data.message

import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.data.conversation.ConversationRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * 单聊已读标记（开发计划 4.9，协议规范 §8.2）：用户看到消息时，对对方发来的最新一条消息发送 `<displayed/>`，
 * 语义为"该消息及之前的都已读"；同时清除本地未读。
 *
 * 同一条消息只发送一次（进程内记录；应用重启后再次打开会话会补发一次，无害）。发送失败（未连接）时不记录，下次打开再发。
 *
 * @property client XMPP 客户端
 * @property messages 消息仓库
 * @property conversations 会话仓库
 * @property ids ID 生成器（已读报文的 ID）
 */
class ReadMarkers(
    private val client: XmppClient,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val ids: IdGenerator,
) {

    private val log = Logs.file(TAG)

    /** 会话 ID → 已经发送过已读标记（或已由其他端发送）的消息 ID */
    private val lastMarked = ConcurrentHashMap<String, String>()

    /**
     * 用户正在查看单聊会话：清除未读，并对最新一条对方发来的消息发送已读标记。
     *
     * @param conversationId 会话 ID（对方 bare JID）
     */
    suspend fun markConversationRead(conversationId: String) {
        conversations.clearUnread(conversationId)
        val last = messages.lastIncoming(conversationId) ?: return
        if (last.retracted || lastMarked[conversationId] == last.msgId) return
        try {
            client.sendMessage(MessageCodec.displayed(conversationId, last.msgId, ids.messageId()), ACK_TIMEOUT_MILLIS)
            lastMarked[conversationId] = last.msgId
            log.info("已发送已读标记：会话={}，msgId={}", conversationId, last.msgId)
        } catch (e: XmppException) {
            log.warn("已读标记发送失败（下次打开会话时重发）：会话={}，{}", conversationId, e.message)
        }
    }

    /**
     * 本人其他端已经发送了已读标记（Carbons 抄送）：本端不必再发。
     *
     * @param conversationId 会话 ID
     * @param msgId 已读到的消息 ID
     */
    fun onMarkedElsewhere(conversationId: String, msgId: String) {
        lastMarked[conversationId] = msgId
    }

    private companion object {
        const val TAG = "ReadMarkers"

        /** 已读报文等待服务器确认的超时 */
        const val ACK_TIMEOUT_MILLIS = 30_000L
    }
}
