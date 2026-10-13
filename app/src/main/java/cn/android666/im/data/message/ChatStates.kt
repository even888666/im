package cn.android666.im.data.message

import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 正在输入状态（开发计划 4.14，协议规范 §8.3，XEP-0085，仅单聊）。
 *
 * 发送：同一会话每 [THROTTLE_MILLIS] 最多发送一次 `composing`；停止输入 [PAUSE_AFTER_MILLIS] 后发送 `paused`；
 * 发出消息或离开聊天页时，如果之前发过 composing 则立即发送 `paused`。
 *
 * 接收：对方 `composing` 时显示"对方正在输入…"，收到 `paused` / 其他状态、对方发来消息、或超过 [EXPIRE_MILLIS]
 * 没有新的 composing（对方断网等）时消失。
 *
 * 状态报文带 `<no-store/>`，不进存档；发送失败只记录日志。
 *
 * @property client XMPP 客户端
 * @property ids ID 生成器
 * @property clock 时间来源（节流用单调时间）
 * @property scope 协程作用域（定时发送 paused、到期清除）
 */
class ChatStates(
    private val client: XmppClient,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {

    private val log = Logs.file(TAG)

    /** 会话 → 上次发送 composing 的时间（单调时间）；发送 paused 后移除 */
    private val composingSentAt = ConcurrentHashMap<String, Long>()

    /** 会话 → 等待发送 paused 的任务 */
    private val pauseJobs = ConcurrentHashMap<String, Job>()

    /** 正在输入的对方（会话 ID 集合） */
    private val typingPeers = MutableStateFlow<Set<String>>(emptySet())

    /** 对方输入状态的到期任务 */
    private val expireJobs = ConcurrentHashMap<String, Job>()

    /**
     * 本人在输入框中输入（文字变化时调用）。
     *
     * @param conversationId 会话 ID（对方 bare JID）
     */
    fun onTyping(conversationId: String) {
        val now = clock.elapsedMillis()
        val last = composingSentAt[conversationId]
        if (last == null || now - last >= THROTTLE_MILLIS) {
            composingSentAt[conversationId] = now
            send(conversationId, STATE_COMPOSING)
        }
        // 重新计时：停止输入一段时间后发送 paused
        pauseJobs.remove(conversationId)?.cancel()
        pauseJobs[conversationId] = scope.launch {
            delay(PAUSE_AFTER_MILLIS)
            pauseJobs.remove(conversationId)
            sendPausedIfComposing(conversationId)
        }
    }

    /**
     * 本人停止输入（发出消息、清空输入框、离开聊天页）：之前发过 composing 时立即发送 paused。
     *
     * @param conversationId 会话 ID
     */
    fun onStopTyping(conversationId: String) {
        pauseJobs.remove(conversationId)?.cancel()
        sendPausedIfComposing(conversationId)
    }

    /**
     * 之前发过 composing（还没发 paused）时发送 paused。
     *
     * @param conversationId 会话 ID
     */
    private fun sendPausedIfComposing(conversationId: String) {
        if (composingSentAt.remove(conversationId) != null) send(conversationId, STATE_PAUSED)
    }

    /**
     * 发送状态报文（不阻塞调用方）。
     *
     * @param conversationId 会话 ID
     * @param state 状态
     */
    private fun send(conversationId: String, state: String) {
        scope.launch {
            try {
                client.sendMessage(MessageCodec.chatState(conversationId, state, ids.messageId()), ACK_TIMEOUT_MILLIS)
            } catch (e: XmppException) {
                log.debug("正在输入状态发送失败：{} {}，{}", conversationId, state, e.message)
            }
        }
    }

    /**
     * 收到对方的状态（由 IncomingMessageHandler 调用）。
     *
     * @param conversationId 会话 ID（对方 bare JID）
     * @param state 状态：composing / paused / active / inactive / gone
     */
    fun onPeerState(conversationId: String, state: String) {
        expireJobs.remove(conversationId)?.cancel()
        if (state == STATE_COMPOSING) {
            typingPeers.value = typingPeers.value + conversationId
            expireJobs[conversationId] = scope.launch {
                delay(EXPIRE_MILLIS)
                expireJobs.remove(conversationId)
                typingPeers.value = typingPeers.value - conversationId
            }
        } else {
            typingPeers.value = typingPeers.value - conversationId
        }
    }

    /**
     * 对方发来了消息：输入状态结束。
     *
     * @param conversationId 会话 ID
     */
    fun onPeerMessage(conversationId: String) {
        if (conversationId in typingPeers.value) onPeerState(conversationId, STATE_ACTIVE)
    }

    /**
     * 对方是否正在输入。
     *
     * @param conversationId 会话 ID
     * @return 持续更新的状态
     */
    fun observePeerTyping(conversationId: String): Flow<Boolean> = typingPeers.map { conversationId in it }.distinctUntilChanged()

    companion object {
        private const val TAG = "ChatStates"

        /** 正在输入 */
        const val STATE_COMPOSING = "composing"

        /** 暂停输入 */
        const val STATE_PAUSED = "paused"

        /** 活跃（不在输入） */
        const val STATE_ACTIVE = "active"

        /** composing 的节流间隔（协议规范 §8.3：每 5 秒最多一次） */
        const val THROTTLE_MILLIS = 5_000L

        /** 停止输入多久后发送 paused（协议规范 §8.3） */
        const val PAUSE_AFTER_MILLIS = 5_000L

        /** 收到 composing 后多久没有新的 composing 就不再显示（对方断网、退出等收不到 paused 的情况，决策记录 A115） */
        const val EXPIRE_MILLIS = 30_000L

        /** 状态报文等待服务器确认的超时 */
        private const val ACK_TIMEOUT_MILLIS = 10_000L
    }
}
