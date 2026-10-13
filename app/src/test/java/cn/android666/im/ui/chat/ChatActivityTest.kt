package cn.android666.im.ui.chat

import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.App
import cn.android666.im.R
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.xmpp.extension.msg.FileInfo
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.extension.msg.ThumbnailInfo
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.message.MessageJson
import cn.android666.im.ui.media.ImagePreviewActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * 聊天页的 Robolectric 测试：使用真实的 App 容器（10001 的用户数据库），打开页面、发送文字，消息出现在列表中。
 */
@RunWith(RobolectricTestRunner::class)
class ChatActivityTest {

    private val app = ApplicationProvider.getApplicationContext<App>()

    @Before
    fun setUp() = runBlocking {
        val db = app.container.userDatabaseHolder.open("10001")
        db.employeeDao().upsert(
            listOf(EmployeeEntity(BOB, "10002", "李四", "lisi", "ls", "d", "", "", "", "", 0, "")),
        )
    }

    @After
    fun tearDown() {
        app.container.userDatabaseHolder.close()
        app.deleteDatabase("im_10001.db")
    }

    /**
     * 推进主线程直到条件满足（Room 查询在后台线程执行）。
     *
     * @param condition 条件
     */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时")
    }

    @Test
    fun open_send_showsBubble() {
        val intent = ChatActivity.intent(app, BOB, ConversationKind.SINGLE)
        ActivityScenario.launch<ChatActivity>(intent).use { scenario ->
            lateinit var list: RecyclerView
            lateinit var toolbar: Toolbar
            scenario.onActivity {
                list = it.findViewById(R.id.list)
                toolbar = it.findViewById(R.id.toolbar)
            }
            waitUntil { toolbar.title == "李四" }
            // 聊天水印：本人工号（组织架构中没有本人时只显示工号）
            scenario.onActivity {
                assertThat(it.findViewById<cn.android666.im.ui.widget.WatermarkView>(R.id.watermark).text).isEqualTo("10001")
            }

            scenario.onActivity {
                it.findViewById<EditText>(R.id.input).setText("你好，李四")
                assertThat(it.findViewById<android.view.View>(R.id.send).isEnabled).isTrue()
                it.findViewById<android.view.View>(R.id.send).performClick()
            }
            waitUntil {
                list.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.bubble)?.text?.toString() == "你好，李四"
            }
            scenario.onActivity {
                // 发送后清空输入框
                assertThat(it.findViewById<EditText>(R.id.input).text.toString()).isEmpty()
            }
        }
    }

    /** 4.17 草稿：打开时恢复，离开时保存 */
    @Test
    fun draft_restoredAndSaved() {
        runBlocking {
            app.container.conversationRepository.saveDraft(BOB, ConversationKind.SINGLE, "上次没写完")
        }
        val intent = ChatActivity.intent(app, BOB, ConversationKind.SINGLE)
        ActivityScenario.launch<ChatActivity>(intent).use { scenario ->
            lateinit var input: EditText
            scenario.onActivity { input = it.findViewById(R.id.input) }
            waitUntil { input.text.toString() == "上次没写完" }

            scenario.onActivity { input.setText("改过的草稿") }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            waitUntil { runBlocking { app.container.conversationRepository.draft(BOB) } == "改过的草稿" }
        }
    }

    /** 文件传输助手：标题为固定名称 */
    @Test
    fun selfConversation_title() {
        val intent = ChatActivity.intent(app, "10001@im.example.com", ConversationKind.SELF)
        ActivityScenario.launch<ChatActivity>(intent).use { scenario ->
            lateinit var toolbar: Toolbar
            scenario.onActivity { toolbar = it.findViewById(R.id.toolbar) }
            waitUntil { toolbar.title == app.getString(R.string.conversation_self) }
        }
    }

    /** 5.4 收到的图片：显示缩略图（隐藏文字气泡），点击打开大图预览 */
    @Test
    fun imageMessage_shownAndOpensPreview() {
        runBlocking {
            val content = MessageContent.Image(
                FileInfo("https://im.example.com/upload/a.jpg", "a.jpg", 100, "image/jpeg", width = 1920, height = 1080),
                ThumbnailInfo("https://im.example.com/upload/a_thumb.jpg", 320, 180, "image/jpeg"),
            )
            app.container.userDatabaseHolder.require().messageDao().insert(
                MessageEntity(
                    msgId = "img", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "image",
                    body = "[图片]", contentJson = MessageJson.encodeContent(content), status = MessageEntity.STATUS_RECEIVED, serverTime = 1,
                ),
            )
        }
        val intent = ChatActivity.intent(app, BOB, ConversationKind.SINGLE)
        ActivityScenario.launch<ChatActivity>(intent).use { scenario ->
            lateinit var list: RecyclerView
            scenario.onActivity { list = it.findViewById(R.id.list) }
            waitUntil { list.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<android.view.View>(R.id.imageBox)?.isVisible == true }
            scenario.onActivity { activity ->
                val item = list.findViewHolderForAdapterPosition(0)!!.itemView
                assertThat(item.findViewById<TextView>(R.id.bubble).isVisible).isFalse()
                // 横图：宽为最大边长 150dp
                val density = activity.resources.displayMetrics.density
                assertThat(item.findViewById<android.view.View>(R.id.image).layoutParams.width).isEqualTo((150 * density).toInt())
                item.findViewById<android.view.View>(R.id.imageBox).performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertThat(started.component!!.className).isEqualTo(ImagePreviewActivity::class.java.name)
                assertThat(started.getStringExtra("url")).isEqualTo("https://im.example.com/upload/a.jpg")
                assertThat(started.getStringExtra("thumbnail")).isEqualTo("https://im.example.com/upload/a_thumb.jpg")
            }
        }
    }

    private companion object {
        const val BOB = "10002@im.example.com"
    }
}
