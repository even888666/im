package cn.android666.im.service.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.ConversationRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** [MessageNotificationPolicy] 与 [MessageNotifier] 测试 */
@RunWith(RobolectricTestRunner::class)
class MessageNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var holder: UserDatabaseHolder? = null

    private var foreground = false
    private var active: String? = null

    @After
    fun tearDown() {
        holder?.close()
    }

    @Test
    fun policy() {
        fun notify(outgoing: Boolean = false, fg: Boolean = false, active: String? = null, muted: Boolean = false, mention: Boolean = false) =
            MessageNotificationPolicy.shouldNotify(outgoing, fg, active, BOB, muted, mention)
        assertThat(notify()).isTrue()
        assertThat(notify(outgoing = true)).isFalse()
        assertThat(notify(fg = true)).isFalse()
        assertThat(notify(active = BOB)).isFalse()
        assertThat(notify(active = "other@x")).isTrue()
        assertThat(notify(muted = true)).isFalse()
        // 免打扰但 @ 了我：仍然通知
        assertThat(notify(muted = true, mention = true)).isTrue()
    }

    /** 被测对象：Room 内存库 + 记录跳转的 Intent */
    private fun TestScope.notifier(): Pair<MessageNotifier, ImDatabase> {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.createAll(context)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(context, ImDatabase::class.java).setQueryCoroutineContext(dispatcher).allowMainThreadQueries().build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        val notifier = MessageNotifier(
            context = context,
            conversations = ConversationRepository(newHolder),
            senderName = { if (it == BOB) "李四" else null },
            isForeground = { foreground },
            activeConversation = { active },
            mainIntent = { Intent("main") },
            chatIntent = { id, kind -> Intent("chat").putExtra("id", id).putExtra("kind", kind.name) },
        )
        return notifier to db
    }

    private fun message(id: String, body: String, from: String = BOB, mention: Boolean = false) = MessageEntity(
        rowId = 1, msgId = id, conversationId = from, senderJid = from, direction = MessageEntity.DIRECTION_IN, type = "text",
        body = body, status = MessageEntity.STATUS_RECEIVED, serverTime = 1_000, mentionMe = mention,
    )

    /** 当前显示的消息通知 */
    private fun posted(): List<Notification> =
        shadowOf(manager).allNotifications.filter { it.channelId == NotificationChannels.MESSAGE }

    @Test
    fun background_postsMergedMessagingStyle() = runTest {
        val (notifier, _) = notifier()

        repeat(9) { notifier.onNewMessage(message("m$it", "第 $it 条")) }

        val notification = posted().single()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        // 最多保留最近 7 条
        assertThat(style.messages.map { it.text.toString() }).containsExactlyElementsIn((2..8).map { "第 $it 条" }).inOrder()
        assertThat(style.messages.first().person!!.name.toString()).isEqualTo("李四")
        assertThat(notification.extras.getString(NotificationCompat.EXTRA_TITLE)).isEqualTo("李四")
        // 锁屏只显示数量
        assertThat(notification.publicVersion!!.extras.getString(NotificationCompat.EXTRA_TEXT)).isEqualTo("收到 7 条新消息")
        // 点击：主界面 → 聊天页
        val intents = shadowOf(notification.contentIntent).savedIntents
        assertThat(intents.map { it.action }).containsExactly("main", "chat").inOrder()
        assertThat(intents[1].getStringExtra("id")).isEqualTo(BOB)
        assertThat(intents[1].getStringExtra("kind")).isEqualTo(ConversationKind.SINGLE.name)
    }

    @Test
    fun foregroundOrActive_noNotification() = runTest {
        val (notifier, _) = notifier()

        foreground = true
        notifier.onNewMessage(message("m1", "x"))
        foreground = false
        active = BOB
        notifier.onNewMessage(message("m2", "x"))

        assertThat(posted()).isEmpty()
    }

    /** 免打扰：不通知；@ 了我时仍然通知 */
    @Test
    fun muted() = runTest {
        val (notifier, db) = notifier()
        db.conversationDao().upsert(ConversationEntity(BOB, ConversationEntity.TYPE_SINGLE, muted = true))

        notifier.onNewMessage(message("m1", "x"))
        assertThat(posted()).isEmpty()

        notifier.onNewMessage(message("m2", "@我", mention = true))
        assertThat(posted()).hasSize(1)
    }

    /** 不同会话分开通知；工号兜底姓名；会话已读（未读由 >0 变为 0）时取消 */
    @Test
    fun perConversation_cancelWhenRead() = runTest {
        val (notifier, _) = notifier()
        val unread = MutableStateFlow(emptyMap<String, Int>())
        notifier.start(MutableSharedFlow(), unread, backgroundScope)
        runCurrent()

        notifier.onNewMessage(message("m1", "x"))
        notifier.onNewMessage(message("m2", "y", from = CAROL))
        assertThat(posted()).hasSize(2)
        assertThat(posted().map { it.extras.getString(NotificationCompat.EXTRA_TITLE) }).containsExactly("李四", "10003")

        // 写库之前的旧数据（未读为 0）不取消刚发出的通知
        unread.value = mapOf(BOB to 0)
        runCurrent()
        assertThat(posted()).hasSize(2)

        unread.value = mapOf(BOB to 1, CAROL to 1)
        runCurrent()
        unread.value = mapOf(BOB to 0, CAROL to 1)
        runCurrent()
        assertThat(posted().map { it.extras.getString(NotificationCompat.EXTRA_TITLE) }).containsExactly("10003")
    }

    /** 订阅新消息流 */
    @Test
    fun start_collectsNewMessages() = runTest {
        val (notifier, _) = notifier()
        val newMessages = MutableSharedFlow<MessageEntity>(extraBufferCapacity = 4)
        notifier.start(newMessages, MutableStateFlow(emptyMap()), backgroundScope)

        newMessages.emit(message("m1", "x"))
        runCurrent()

        assertThat(posted()).hasSize(1)
    }

    private companion object {
        const val BOB = "10002@im.example.com"
        const val CAROL = "10003@im.example.com"
    }
}
