package cn.android666.im.data.message

import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/** 撤回的结果 */
sealed interface RetractResult {
    /** 已撤回 */
    data object Success : RetractResult

    /** 超过时限（2 分钟） */
    data object TooLate : RetractResult

    /** 不能撤回（不是本人发出的、还没发出去、已经撤回过） */
    data object NotAllowed : RetractResult

    /** 发送失败（未连接、超时） */
    data object Failed : RetractResult
}

/**
 * 撤回（开发计划 4.12，协议规范 §8.4）。
 *
 * 发送：本人发出且已发出的消息、2 分钟内可撤回；服务器确认后本地立即显示"你撤回了一条消息"。
 * 服务器（biz 插件，4.20）校验不通过时退回错误（`retract-timeout` / `retract-forbidden`），此时恢复原消息并通过
 * [rejections] 通知界面。
 *
 * @property client XMPP 客户端
 * @property repository 消息仓库
 * @property serverClock 服务器时间（判断时限）
 * @property ids ID 生成器（撤回报文的 ID）
 * @property selfJid 本人 bare JID
 * @property selfName 本人姓名（撤回报文的降级文本"张三撤回了一条消息"）；取不到时用工号
 * @property timeLimitMillis 撤回时限
 */
class Retractions(
    private val client: XmppClient,
    private val repository: MessageRepository,
    private val serverClock: ServerClock,
    private val ids: IdGenerator,
    private val selfJid: () -> String?,
    private val selfName: suspend () -> String?,
    private val timeLimitMillis: Long = TIME_LIMIT_MILLIS,
) {

    private val log = Logs.file(TAG)

    /** 已发送、还可能被服务器拒绝的撤回：撤回报文 ID → 撤回前的原消息 */
    private val pending = ConcurrentHashMap<String, MessageEntity>()

    private val rejectionFlow = MutableSharedFlow<String>(extraBufferCapacity = REJECTION_BUFFER)

    /** 服务器拒绝撤回时发出错误码（retract-timeout / retract-forbidden 等），界面提示 */
    val rejections: SharedFlow<String> = rejectionFlow.asSharedFlow()

    /**
     * 撤回本人发出的一条消息。
     *
     * @param rowId 消息 rowId
     * @return 结果
     */
    suspend fun retract(rowId: Long): RetractResult {
        val self = selfJid() ?: return RetractResult.Failed
        val message = repository.get(rowId) ?: return RetractResult.NotAllowed
        if (message.direction != MessageEntity.DIRECTION_OUT || message.retracted ||
            message.status == MessageEntity.STATUS_SENDING || message.status == MessageEntity.STATUS_FAILED
        ) {
            return RetractResult.NotAllowed
        }
        if (serverClock.nowMillis() - message.serverTime > timeLimitMillis) return RetractResult.TooLate
        val name = selfName() ?: self.substringBefore('@')
        val id = ids.messageId()
        pending[id] = message
        try {
            client.sendMessage(MessageCodec.retract(message.conversationId, message.msgId, "$name$NOTICE_SUFFIX", id), ACK_TIMEOUT_MILLIS)
        } catch (e: XmppException) {
            pending.remove(id)
            log.warn("撤回发送失败：msgId={}，{}", message.msgId, e.message)
            return RetractResult.Failed
        }
        val type = if (message.conversationId == self) ConversationEntity.TYPE_SELF else ConversationEntity.TYPE_SINGLE
        repository.applyRetract(
            conversationId = message.conversationId,
            msgId = message.msgId,
            retractedBy = self,
            direction = MessageEntity.DIRECTION_OUT,
            time = serverClock.nowMillis(),
            notice = SELF_NOTICE,
            conversationType = type,
        )
        log.info("已撤回：msgId={}，会话={}", message.msgId, message.conversationId)
        return RetractResult.Success
    }

    /**
     * 收到错误报文时调用：是本端发出的撤回被拒绝，则恢复原消息。
     *
     * @param stanzaId 错误报文的 ID（即撤回报文的 ID）
     * @param errorCode 错误码
     * @return 是撤回被拒绝（已处理）时为 true
     */
    suspend fun onRejected(stanzaId: String, errorCode: String): Boolean {
        val original = pending.remove(stanzaId) ?: return false
        repository.revertRetract(original)
        rejectionFlow.tryEmit(errorCode)
        log.warn("撤回被服务器拒绝：msgId={}，错误={}", original.msgId, errorCode)
        return true
    }

    companion object {
        private const val TAG = "Retractions"

        /** 撤回时限：2 分钟（协议规范 §8.4） */
        const val TIME_LIMIT_MILLIS = 2 * 60 * 1000L

        /** 撤回报文降级文本的后缀："张三" + 后缀 */
        const val NOTICE_SUFFIX = "撤回了一条消息"

        /** 本人撤回后的会话预览 */
        const val SELF_NOTICE = "你撤回了一条消息"

        /** 对方撤回、报文中没有降级文本时的会话预览 */
        const val PEER_NOTICE = "对方撤回了一条消息"

        /** 撤回报文等待服务器确认的超时 */
        private const val ACK_TIMEOUT_MILLIS = 30_000L

        /** 拒绝通知的缓冲 */
        private const val REJECTION_BUFFER = 8
    }
}
