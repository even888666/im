package cn.android666.im.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.core.database.entity.MessageEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ConversationRepository] 测试：联表查询（员工姓名、最后一条消息状态）、排序、总未读、删除 */
@RunWith(RobolectricTestRunner::class)
class ConversationRepositoryTest {

    private var holder: UserDatabaseHolder? = null

    @After
    fun tearDown() {
        holder?.close()
    }

    private fun TestScope.open(): Pair<ConversationRepository, ImDatabase> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        return ConversationRepository(newHolder) to db
    }

    private fun message(id: String, conv: String, direction: Int, status: Int, time: Long) = MessageEntity(
        msgId = id, conversationId = conv, senderJid = if (direction == MessageEntity.DIRECTION_OUT) SELF else conv,
        direction = direction, type = "text", body = "b-$id", status = status, serverTime = time,
    )

    @Test
    fun list_joinsEmployeeAndLastMessageState() = runTest {
        val (repository, db) = open()
        db.employeeDao().upsert(
            listOf(EmployeeEntity(BOB, "10002", "李四", "lisi", "ls", "d1", "", "", "", "", 0, "h1")),
        )
        val failed = db.messageDao().insert(message("m1", BOB, MessageEntity.DIRECTION_OUT, MessageEntity.STATUS_FAILED, 300))
        val incoming = db.messageDao().insert(message("m2", CAROL, MessageEntity.DIRECTION_IN, MessageEntity.STATUS_RECEIVED, 200))
        val sending = db.messageDao().insert(message("m3", SELF, MessageEntity.DIRECTION_OUT, MessageEntity.STATUS_SENDING, 100))
        db.conversationDao().upsert(ConversationEntity(BOB, ConversationEntity.TYPE_SINGLE, failed, "失败的", 300))
        db.conversationDao().upsert(ConversationEntity(CAROL, ConversationEntity.TYPE_SINGLE, incoming, "收到的", 200, unreadCount = 2))
        db.conversationDao().upsert(ConversationEntity(SELF, ConversationEntity.TYPE_SELF, sending, "发送中", 100))
        // 没有消息也没有草稿的会话不显示；置顶的排最前
        db.conversationDao().upsert(ConversationEntity("empty@im.example.com", ConversationEntity.TYPE_SINGLE))
        db.conversationDao().upsert(ConversationEntity(GROUP, ConversationEntity.TYPE_GROUP, draft = "草稿", pinned = true, pinnedAt = 1))

        val list = repository.observeConversations().first()

        assertThat(list.map { it.id }).containsExactly(GROUP, BOB, CAROL, SELF).inOrder()
        val bob = list[1]
        assertThat(bob.peerName).isEqualTo("李四")
        assertThat(bob.avatarHash).isEqualTo("h1")
        assertThat(bob.lastMessageState).isEqualTo(LastMessageState.FAILED)
        assertThat(list[2].peerName).isNull()
        assertThat(list[2].lastMessageState).isEqualTo(LastMessageState.NONE)
        assertThat(list[2].unreadCount).isEqualTo(2)
        assertThat(list[3].kind).isEqualTo(ConversationKind.SELF)
        assertThat(list[3].lastMessageState).isEqualTo(LastMessageState.SENDING)
        assertThat(list[0].kind).isEqualTo(ConversationKind.GROUP)
        assertThat(list[0].draft).isEqualTo("草稿")
        assertThat(list[0].pinned).isTrue()
    }

    @Test
    fun totalUnread_excludesMuted() = runTest {
        val (repository, db) = open()
        db.conversationDao().upsert(ConversationEntity(BOB, ConversationEntity.TYPE_SINGLE, unreadCount = 3))
        db.conversationDao().upsert(ConversationEntity(CAROL, ConversationEntity.TYPE_SINGLE, unreadCount = 5, muted = true))

        assertThat(repository.observeTotalUnread().first()).isEqualTo(3)

        repository.clearUnread(BOB)
        assertThat(repository.observeTotalUnread().first()).isEqualTo(0)
    }

    @Test
    fun delete_removesConversationAndMessages() = runTest {
        val (repository, db) = open()
        val row = db.messageDao().insert(message("m1", BOB, MessageEntity.DIRECTION_IN, MessageEntity.STATUS_RECEIVED, 1))
        db.messageDao().insert(message("m2", CAROL, MessageEntity.DIRECTION_IN, MessageEntity.STATUS_RECEIVED, 1))
        db.conversationDao().upsert(ConversationEntity(BOB, ConversationEntity.TYPE_SINGLE, row, "x", 1))

        repository.delete(BOB)

        assertThat(db.conversationDao().get(BOB)).isNull()
        assertThat(db.messageDao().get(BOB, "m1")).isNull()
        assertThat(db.messageDao().get(CAROL, "m2")).isNotNull()
    }

    /** 未登录：空列表、未读为 0 */
    @Test
    fun loggedOut_empty() = runTest {
        val (repository, _) = open()
        holder!!.close()

        assertThat(repository.observeConversations().first()).isEmpty()
        assertThat(repository.observeTotalUnread().first()).isEqualTo(0)
    }

    private companion object {
        const val SELF = "10001@im.example.com"
        const val BOB = "10002@im.example.com"
        const val CAROL = "10003@im.example.com"
        const val GROUP = "g1@conference.im.example.com"
    }
}
