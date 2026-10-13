package cn.android666.im.ui.forward

import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.App
import cn.android666.im.R
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.core.xmpp.extension.msg.ForwardedItem
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.message.MessageJson
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 选择转发对象、合并转发的聊天记录两个页面的 Robolectric 测试（真实 App 容器，10001 的用户数据库） */
@RunWith(RobolectricTestRunner::class)
class ForwardPagesTest {

    private val app = ApplicationProvider.getApplicationContext<App>()

    @Before
    fun setUp() = runBlocking {
        val db = app.container.userDatabaseHolder.open("10001")
        db.employeeDao().upsert(listOf(EmployeeEntity(BOB, "10002", "李四", "lisi", "ls", "d", "", "", "", "", 0, "")))
        db.conversationDao().upsert(ConversationEntity(BOB, ConversationEntity.TYPE_SINGLE, draft = "x"))
    }

    @After
    fun tearDown() {
        app.container.userDatabaseHolder.close()
        app.deleteDatabase("im_10001.db")
    }

    /** 推进主线程直到条件满足 */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时")
    }

    private fun itemText(list: RecyclerView, position: Int, id: Int): String? =
        list.findViewHolderForAdapterPosition(position)?.itemView?.findViewById<TextView>(id)?.text?.toString()

    @Test
    fun forwardTarget_listsFileAssistantAndRecent() {
        ActivityScenario.launch(ForwardTargetActivity::class.java).use { scenario ->
            lateinit var list: RecyclerView
            scenario.onActivity { list = it.findViewById(R.id.list) }
            waitUntil { itemText(list, 1, R.id.name) == "李四" }
            assertThat(itemText(list, 0, R.id.name)).isEqualTo("文件传输助手")
        }
    }

    @Test
    fun mergedMessage_showsItemsWithNames() {
        val content = MessageContent.Merged(
            "张三和李四的聊天记录",
            listOf(
                ForwardedItem("m1", "$BOB/pc", "第一条", MessageContent.Text, 1_000),
                ForwardedItem("m2", "10009@im.example.com", "第二条", MessageContent.Text, null),
            ),
        )
        val intent = Intent(app, MergedMessageActivity::class.java).putExtra("content", MessageJson.encodeContent(content))
        ActivityScenario.launch<MergedMessageActivity>(intent).use { scenario ->
            lateinit var list: RecyclerView
            lateinit var toolbar: Toolbar
            scenario.onActivity {
                list = it.findViewById(R.id.list)
                toolbar = it.findViewById(R.id.toolbar)
            }
            waitUntil { itemText(list, 0, R.id.name) == "李四" }
            assertThat(toolbar.title).isEqualTo("张三和李四的聊天记录")
            assertThat(itemText(list, 0, R.id.body)).isEqualTo("第一条")
            // 组织架构中没有：显示工号
            assertThat(itemText(list, 1, R.id.name)).isEqualTo("10009")
        }
    }

    private companion object {
        const val BOB = "10002@im.example.com"
    }
}
