package cn.android666.im.ui.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ConversationListViewModel] 测试：列表、标为已读、删除 */
@RunWith(RobolectricTestRunner::class)
class ConversationListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var holder: UserDatabaseHolder
    private lateinit var db: ImDatabase

    @Before
    fun setUp() {
        holder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(mainDispatcherRule.testDispatcher)
                .allowMainThreadQueries()
                .build()
        }
        db = holder.open("10001")
    }

    @After
    fun tearDown() = holder.close()

    @Test
    fun list_markRead_delete() = runTest(mainDispatcherRule.testDispatcher) {
        val row = db.messageDao().insert(
            MessageEntity(
                msgId = "m1", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "text",
                body = "你好", status = MessageEntity.STATUS_RECEIVED, serverTime = 1,
            ),
        )
        db.conversationDao().upsert(ConversationEntity(BOB, ConversationEntity.TYPE_SINGLE, row, "你好", 1, unreadCount = 2))
        val vm = ConversationListViewModel(ConversationRepository(holder))

        vm.conversations.test {
            // 加载中为 null，之后是列表
            assertThat(awaitItem()).isNull()
            assertThat(awaitItem()!!.single().unreadCount).isEqualTo(2)

            vm.markRead(BOB)
            runCurrent()
            assertThat(awaitItem()!!.single().unreadCount).isEqualTo(0)

            vm.delete(BOB)
            runCurrent()
            assertThat(awaitItem()).isEmpty()
        }
        assertThat(db.messageDao().get(BOB, "m1")).isNull()
    }

    /** 未登录时删除：只记录日志，不崩溃 */
    @Test
    fun delete_loggedOut_noCrash() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = ConversationListViewModel(ConversationRepository(holder))
        holder.close()

        vm.delete(BOB)
        vm.markRead(BOB)
        runCurrent()
    }

    private companion object {
        const val BOB = "10002@im.example.com"
    }
}
