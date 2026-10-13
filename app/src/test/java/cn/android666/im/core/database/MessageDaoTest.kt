package cn.android666.im.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 消息与会话 DAO 测试（开发计划 4.1）：去重约束、发送状态、已读、会话排序与未读 */
@RunWith(RobolectricTestRunner::class)
class MessageDaoTest {

    private lateinit var db: ImDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun msg(id: String, conv: String = "b@x", time: Long = 1000, direction: Int = MessageEntity.DIRECTION_OUT, status: Int = 0) =
        MessageEntity(
            msgId = id, conversationId = conv, senderJid = if (direction == 1) "a@x" else conv, direction = direction,
            type = "text", body = "hi $id", status = status, serverTime = time,
        )

    /** 同一会话同一消息 ID 只插入一次；不同会话可以相同 */
    @Test
    fun insert_dedupByConversationAndMsgId() = runTest {
        val dao = db.messageDao()
        assertThat(dao.insert(msg("m1"))).isGreaterThan(0L)
        assertThat(dao.insert(msg("m1").copy(body = "重复"))).isEqualTo(-1L)
        assertThat(dao.insert(msg("m1", conv = "c@x"))).isGreaterThan(0L)
        assertThat(dao.get("b@x", "m1")?.body).isEqualTo("hi m1")
    }

    @Test
    fun statusTransitions() = runTest {
        val dao = db.messageDao()
        val row = dao.insert(msg("m1"))
        assertThat(dao.pending().map { it.msgId }).containsExactly("m1")

        dao.updateStatus(row, MessageEntity.STATUS_SENT)
        assertThat(dao.pending()).isEmpty()
        assertThat(dao.markDelivered("b@x", "m1")).isEqualTo(1)
        assertThat(dao.getByRowId(row)?.status).isEqualTo(MessageEntity.STATUS_DELIVERED)

        // 已读之后再收到送达回执不回退
        dao.markReadUpTo("b@x", 1000)
        assertThat(dao.markDelivered("b@x", "m1")).isEqualTo(0)
        assertThat(dao.getByRowId(row)?.status).isEqualTo(MessageEntity.STATUS_READ)
    }

    /** 已读标记：该时间及之前发出的消息都已读，失败的不变 */
    @Test
    fun markReadUpTo() = runTest {
        val dao = db.messageDao()
        dao.insert(msg("m1", time = 1000, status = MessageEntity.STATUS_SENT))
        dao.insert(msg("m2", time = 2000, status = MessageEntity.STATUS_DELIVERED))
        dao.insert(msg("m3", time = 3000, status = MessageEntity.STATUS_SENT))
        dao.insert(msg("m4", time = 1500, status = MessageEntity.STATUS_FAILED))

        assertThat(dao.markReadUpTo("b@x", 2000)).isEqualTo(2)
        assertThat(dao.get("b@x", "m3")?.status).isEqualTo(MessageEntity.STATUS_SENT)
        assertThat(dao.get("b@x", "m4")?.status).isEqualTo(MessageEntity.STATUS_FAILED)
    }

    @Test
    fun latestOrderingAndIncomingQueries() = runTest {
        val dao = db.messageDao()
        dao.insert(msg("m1", time = 1000, direction = 0, status = 10))
        dao.insert(msg("m2", time = 3000))
        dao.insert(msg("m3", time = 2000, direction = 0, status = 10))
        // 同一时间按 rowId
        dao.insert(msg("m4", time = 3000))

        dao.observeLatest("b@x", 3).test {
            assertThat(awaitItem().map { it.msgId }).containsExactly("m4", "m2", "m3").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(dao.last("b@x")?.msgId).isEqualTo("m4")
        assertThat(dao.lastIncoming("b@x")?.msgId).isEqualTo("m3")
        assertThat(dao.countIncomingAfter("b@x", 1000)).isEqualTo(1)
        assertThat(dao.maxServerTime()).isEqualTo(3000)
        assertThat(dao.getOutgoing("m2")?.msgId).isEqualTo("m2")
    }

    @Test
    fun conversations_orderingUnreadDraft() = runTest {
        val dao = db.conversationDao()
        dao.upsert(ConversationEntity("a", ConversationEntity.TYPE_SINGLE, lastMsgRowId = 1, lastMsgTime = 100, unreadCount = 2))
        dao.upsert(ConversationEntity("b", ConversationEntity.TYPE_SINGLE, lastMsgRowId = 2, lastMsgTime = 300, unreadCount = 1, muted = true))
        dao.upsert(ConversationEntity("c", ConversationEntity.TYPE_SINGLE, lastMsgRowId = 3, lastMsgTime = 50, pinned = true, pinnedAt = 1))
        // 没有消息也没有草稿：不显示
        dao.upsert(ConversationEntity("d", ConversationEntity.TYPE_SINGLE))

        assertThat(dao.observeAll().first().map { it.id }).containsExactly("c", "b", "a").inOrder()
        // 免打扰的会话不计入总未读
        assertThat(dao.observeTotalUnread().first()).isEqualTo(2)

        dao.clearUnread("a")
        assertThat(dao.observeTotalUnread().first()).isEqualTo(0)
        dao.setDraft("d", "草稿")
        assertThat(dao.observeAll().first().map { it.id }).contains("d")
    }
}
