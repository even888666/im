package cn.android666.im.service.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.TaskStackBuilder
import cn.android666.im.R
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.data.conversation.ConversationItem
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.ConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 是否需要为一条新消息发通知（开发计划 4.13，纯逻辑）。
 */
object MessageNotificationPolicy {

    /**
     * 判断。
     *
     * @param outgoing 是否本人发出（其他端同步过来的）
     * @param foreground App 是否在前台（前台时界面上有未读角标，不发系统通知，决策记录 A107）
     * @param activeConversation 用户正在查看的会话
     * @param conversationId 消息所属会话
     * @param muted 会话是否免打扰
     * @param mentionMe 是否 @ 了我（免打扰也要通知，功能清单 §8"@ 提醒"）
     * @return 需要通知时为 true
     */
    fun shouldNotify(
        outgoing: Boolean,
        foreground: Boolean,
        activeConversation: String?,
        conversationId: String,
        muted: Boolean,
        mentionMe: Boolean,
    ): Boolean = !outgoing && !foreground && activeConversation != conversationId && (!muted || mentionMe)
}

/**
 * 新消息通知（开发计划 4.13）：App 在后台时，为不在查看中、未免打扰的会话发通知；同一会话的消息合并在一条
 * MessagingStyle 通知中（最多显示最近 [MAX_LINES] 条）；点击进入该会话的聊天页（返回时回到主界面）；
 * 会话已读（未读数变为 0，包括其他端已读）时自动取消。
 *
 * 跳转用的 Intent 由外部提供（service 包不依赖 ui 包，Android工程架构.md §3.3）。
 *
 * @property context 应用 Context
 * @property conversations 会话（免打扰、类型、对方姓名）
 * @property senderName 发送方姓名（组织架构）；取不到时显示工号
 * @property isForeground App 是否在前台
 * @property activeConversation 用户正在查看的会话
 * @property mainIntent 主界面 Intent
 * @property chatIntent 聊天页 Intent
 */
class MessageNotifier(
    private val context: Context,
    private val conversations: ConversationRepository,
    private val senderName: suspend (jid: String) -> String?,
    private val isForeground: () -> Boolean,
    private val activeConversation: () -> String?,
    private val mainIntent: () -> Intent,
    private val chatIntent: (conversationId: String, kind: ConversationKind) -> Intent,
) {

    private val log = Logs.file(TAG)

    /**
     * 通知中的一行。
     *
     * @property sender 发送方姓名
     * @property senderJid 发送方 JID（区分不同的人）
     * @property text 内容
     * @property time 时间（毫秒）
     */
    data class Line(val sender: String, val senderJid: String, val text: String, val time: Long)

    /** 已发出通知的会话 → 通知中的消息（新的在后） */
    private val lines = ConcurrentHashMap<String, ArrayDeque<Line>>()

    /**
     * 发出通知后、已经观察到"有未读"的会话。只有它们的未读变为 0 时才取消通知：
     * 未读数的变化是异步推送的，发通知时可能还会收到写库之前的旧数据（未读为 0），不能据此取消。
     */
    private val unreadSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val manager = NotificationManagerCompat.from(context)

    /**
     * 开始工作：订阅新消息与未读数变化。
     *
     * @param newMessages 新保存的收到的消息
     * @param unreadCounts 各会话未读数
     * @param scope 应用级协程作用域
     */
    fun start(newMessages: Flow<MessageEntity>, unreadCounts: Flow<Map<String, Int>>, scope: CoroutineScope) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { newMessages.collect { onNewMessage(it) } }
        scope.launch {
            unreadCounts.collect { counts ->
                lines.keys.forEach { id ->
                    if ((counts[id] ?: 0) > 0) {
                        unreadSeen += id
                    } else if (id in unreadSeen) {
                        cancel(id)
                    }
                }
            }
        }
    }

    /**
     * 处理一条新消息。
     *
     * @param message 消息
     */
    internal suspend fun onNewMessage(message: MessageEntity) {
        val conversation = conversations.get(message.conversationId)
        val notify = MessageNotificationPolicy.shouldNotify(
            outgoing = message.direction == MessageEntity.DIRECTION_OUT,
            foreground = isForeground(),
            activeConversation = activeConversation(),
            conversationId = message.conversationId,
            muted = conversation?.muted == true,
            mentionMe = message.mentionMe,
        )
        if (!notify) return
        val name = senderName(message.senderJid) ?: message.senderJid.substringBefore('@')
        val queue = lines.getOrPut(message.conversationId) { ArrayDeque() }
        val snapshot = synchronized(queue) {
            queue.addLast(Line(name, message.senderJid, message.body, message.serverTime))
            while (queue.size > MAX_LINES) queue.removeFirst()
            queue.toList()
        }
        post(message.conversationId, conversation, name, snapshot)
    }

    /**
     * 发出（或更新）一个会话的通知。
     *
     * @param conversationId 会话 ID
     * @param conversation 会话（可能还没有记录）
     * @param peerName 对方姓名（单聊的标题）
     * @param snapshot 通知中的消息
     */
    @SuppressLint("MissingPermission")
    private fun post(conversationId: String, conversation: ConversationItem?, peerName: String, snapshot: List<Line>) {
        if (!manager.areNotificationsEnabled()) {
            log.info("通知权限未开启，不发通知：会话={}", conversationId)
            return
        }
        val kind = conversation?.kind ?: ConversationKind.SINGLE
        val notification = build(conversationId, kind, peerName, snapshot)
        try {
            manager.notify(TAG_MESSAGE, notificationId(conversationId), notification)
            log.info("新消息通知：会话={}，合并 {} 条", conversationId, snapshot.size)
        } catch (e: SecurityException) {
            log.warn("发通知失败（没有通知权限）", e)
        }
    }

    /**
     * 构建通知。
     *
     * @param conversationId 会话 ID
     * @param kind 会话类型
     * @param title 标题
     * @param snapshot 通知中的消息
     * @return 通知
     */
    internal fun build(conversationId: String, kind: ConversationKind, title: String, snapshot: List<Line>): Notification {
        val me = Person.Builder().setName(context.getString(R.string.notification_me)).setKey(KEY_ME).build()
        val style = NotificationCompat.MessagingStyle(me)
        if (kind == ConversationKind.GROUP) {
            style.setConversationTitle(title)
            style.setGroupConversation(true)
        }
        snapshot.forEach { line ->
            style.addMessage(line.text, line.time, Person.Builder().setName(line.sender).setKey(line.senderJid).build())
        }
        val intent = TaskStackBuilder.create(context)
            .addNextIntent(mainIntent())
            .addNextIntent(chatIntent(conversationId, kind))
            .getPendingIntent(notificationId(conversationId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // 锁屏上只显示"收到新消息"，不显示发送方和内容
        val publicVersion = NotificationCompat.Builder(context, NotificationChannels.MESSAGE)
            .setSmallIcon(R.drawable.ic_tab_messages)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.resources.getQuantityString(R.plurals.notification_public_text, snapshot.size, snapshot.size))
            .build()
        return NotificationCompat.Builder(context, NotificationChannels.MESSAGE)
            .setSmallIcon(R.drawable.ic_tab_messages)
            .setContentTitle(title)
            .setContentText(snapshot.last().text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setNumber(snapshot.size)
            .setWhen(snapshot.last().time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
    }

    /**
     * 取消一个会话的通知（会话已读）。
     *
     * @param conversationId 会话 ID
     */
    fun cancel(conversationId: String) {
        unreadSeen -= conversationId
        if (lines.remove(conversationId) != null) {
            manager.cancel(TAG_MESSAGE, notificationId(conversationId))
            log.info("取消通知：会话={}", conversationId)
        }
    }

    companion object {
        private const val TAG = "MessageNotifier"

        /** 通知的 tag（与前台服务等其他通知区分） */
        const val TAG_MESSAGE = "message"

        /** 一条通知中最多显示的消息数 */
        const val MAX_LINES = 7

        /** "我"在 MessagingStyle 中的 key */
        private const val KEY_ME = "me"

        /**
         * 会话的通知 ID。
         *
         * @param conversationId 会话 ID
         * @return 通知 ID
         */
        fun notificationId(conversationId: String): Int = conversationId.hashCode()
    }
}
