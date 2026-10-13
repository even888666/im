package cn.android666.im.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.database.entity.SyncStateEntity
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerCapabilities
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.data.message.IncomingMessageHandler
import cn.android666.im.data.message.MessageRepository
import cn.android666.im.data.message.ReadMarkers
import cn.android666.im.data.message.SendQueue
import cn.android666.im.data.session.SessionContext
import cn.android666.im.testing.FakeArchive
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [MamSyncer] 与 [MamPager] 测试：Room 内存库 + [FakeXmppClient] + [FakeArchive]。
 */
@RunWith(RobolectricTestRunner::class)
class MamSyncerTest {

    private val client = FakeXmppClient()
    private val archive = FakeArchive(client)
    private var holder: UserDatabaseHolder? = null

    private val ids = object : IdGenerator {
        private var next = 0
        override fun messageId() = "id${++next}"
        override fun roomId() = "g1"
        override fun callId() = "c1"
        override fun uuid() = "q${++next}"
    }

    @After
    fun tearDown() {
        holder?.close()
    }

    /**
     * 测试环境。
     *
     * @property syncer 被测对象
     * @property db 数据库
     * @property history 历史加载
     */
    private class Rig(val syncer: MamSyncer, val db: ImDatabase, val history: HistoryLoader)

    /**
     * 创建测试环境。服务器时间 = 虚拟时间 + [NOW]。
     *
     * @param pageSize 每页条数
     * @param maxPages 单轮最多页数
     * @return 测试环境
     */
    private suspend fun TestScope.rig(pageSize: Int = 100, maxPages: Int = MamSyncer.MAX_PAGES): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        val clock = object : Clock {
            override fun nowMillis() = NOW + testScheduler.currentTime
            override fun elapsedMillis() = testScheduler.currentTime
        }
        client.login(Credential("10001", "x"))
        client.iqResponder = archive.responder()
        val selfJid = { newHolder.username?.let { "$it@im.example.com" } }
        val queue = SendQueue(client, newHolder, backgroundScope, clock)
        val messages = MessageRepository(newHolder, queue, ServerClock(clock), ids, selfJid)
        val readMarkers = ReadMarkers(client, messages, ConversationRepository(newHolder), ids)
        val handler = IncomingMessageHandler(client, messages, readMarkers, ServerClock(clock), ids, selfJid, backgroundScope)
        runCurrent()
        return Rig(
            MamSyncer(MamPager(client), handler, newHolder, ServerClock(clock), ids, pageSize, maxPages),
            db,
            HistoryLoader(MamPager(client), handler, newHolder, ids, pageSize),
        )
    }

    /** 对方发来的消息 */
    private fun fromBob(id: String, body: String = "b-$id") =
        "<message xmlns='jabber:client' type='chat' id='$id' from='$BOB/pc' to='$SELF'><body>$body</body>" +
            "<request xmlns='urn:xmpp:receipts'/></message>"

    /** 本人（其他端）发给 Bob 的消息 */
    private fun toBob(id: String) =
        "<message xmlns='jabber:client' type='chat' id='$id' from='$SELF/pc' to='$BOB'><body>mine</body></message>"

    /** 首次登录：只拉 7 天，不计未读，不回执；保存游标与存档 ID */
    @Test
    fun initialSync_last7Days_noUnread() = runTest {
        val rig = rig()
        archive.add(NOW - 8 * DAY, fromBob("tooOld"))
        val recent = archive.add(NOW - DAY, fromBob("m1"))
        archive.add(NOW - DAY + 1000, toBob("m2"))

        assertThat(rig.syncer.syncSelf()).isEqualTo(2)

        val query = archive.queries.single()
        assertThat(query.start).isEqualTo(NOW - MamSyncer.INITIAL_RANGE_MILLIS)
        assertThat(query.after).isNull()
        assertThat(rig.db.messageDao().get(BOB, "tooOld")).isNull()
        val m1 = rig.db.messageDao().get(BOB, "m1")!!
        assertThat(m1.stanzaId).isEqualTo(recent)
        assertThat(m1.serverTime).isEqualTo(NOW - DAY)
        assertThat(rig.db.messageDao().get(BOB, "m2")!!.direction).isEqualTo(MessageEntity.DIRECTION_OUT)
        val conversation = rig.db.conversationDao().get(BOB)!!
        assertThat(conversation.unreadCount).isEqualTo(0)
        assertThat(conversation.lastMsgPreview).isEqualTo("mine")
        // 补拉的消息不回执
        assertThat(client.sentMessages).isEmpty()
        assertThat(rig.db.syncStateDao().get(MamSyncer.KEY_SELF)).isEqualTo("a3")
    }

    /** 有游标：从游标之后拉取，计未读；多页直到 complete */
    @Test
    fun incremental_afterCursor_multiplePages() = runTest {
        val rig = rig()
        repeat(3) { archive.add(NOW - DAY + it, fromBob("old$it")) }
        rig.db.syncStateDao().put(SyncStateEntity(MamSyncer.KEY_SELF, "a3"))
        repeat(150) { archive.add(NOW + it, fromBob("new$it")) }

        assertThat(rig.syncer.syncSelf()).isEqualTo(150)

        assertThat(archive.queries.map { it.after }).containsExactly("a3", "a103").inOrder()
        assertThat(archive.queries.all { it.start == null }).isTrue()
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(150)
        assertThat(rig.db.messageDao().get(BOB, "old0")).isNull()
        assertThat(rig.db.syncStateDao().get(MamSyncer.KEY_SELF)).isEqualTo("a153")
    }

    /** 没有游标但本地有消息：从最后一条时间 − 5 分钟开始；重复的消息补上存档 ID */
    @Test
    fun noCursorWithMessages_timeFilterWithOverlap() = runTest {
        val rig = rig()
        rig.db.messageDao().insert(
            MessageEntity(
                msgId = "known", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "text",
                body = "x", status = MessageEntity.STATUS_RECEIVED, serverTime = NOW,
            ),
        )
        val knownArchiveId = archive.add(NOW, fromBob("known"))
        archive.add(NOW + 1000, fromBob("later"))

        rig.syncer.syncSelf()

        assertThat(archive.queries.single().start).isEqualTo(NOW - MamSyncer.OVERLAP_MILLIS)
        assertThat(rig.db.messageDao().get(BOB, "known")!!.stanzaId).isEqualTo(knownArchiveId)
        assertThat(rig.db.messageDao().get(BOB, "later")).isNotNull()
    }

    /** 游标失效（item-not-found）：清除游标，改用时间过滤 */
    @Test
    fun invalidCursor_fallsBackToTime() = runTest {
        val rig = rig()
        rig.db.syncStateDao().put(SyncStateEntity(MamSyncer.KEY_SELF, "gone"))
        archive.invalidCursor = "gone"
        archive.add(NOW, fromBob("m1"))

        rig.syncer.syncSelf()

        assertThat(archive.queries.map { it.after }).containsExactly("gone", null).inOrder()
        assertThat(rig.db.messageDao().get(BOB, "m1")).isNotNull()
        assertThat(rig.db.syncStateDao().get(MamSyncer.KEY_SELF)).isEqualTo("a1")
    }

    /** 其他错误向上抛出，游标不变 */
    @Test
    fun otherError_thrown() = runTest {
        val rig = rig()
        rig.db.syncStateDao().put(SyncStateEntity(MamSyncer.KEY_SELF, "a1"))
        client.iqResponder = { throw XmppException.Timeout() }

        val e = runCatching { rig.syncer.syncSelf() }.exceptionOrNull()

        assertThat(e).isInstanceOf(XmppException.Timeout::class.java)
        assertThat(rig.db.syncStateDao().get(MamSyncer.KEY_SELF)).isEqualTo("a1")
        assertThat(rig.syncer.syncing.value).isFalse()
    }

    /** IQ 结果先于本页最后一条到达：等最后一条到了再处理 */
    @Test
    fun lastResultArrivesAfterFin() = runTest {
        val rig = rig()
        rig.db.syncStateDao().put(SyncStateEntity(MamSyncer.KEY_SELF, "a0"))
        archive.lateLastIn = backgroundScope
        archive.add(NOW, fromBob("m1"))
        archive.add(NOW + 1, fromBob("m2"))

        val result = async(start = CoroutineStart.UNDISPATCHED) { rig.syncer.syncSelf() }
        testScheduler.advanceTimeBy(200)
        runCurrent()

        assertThat(result.await()).isEqualTo(2)
        assertThat(rig.db.messageDao().get(BOB, "m2")).isNotNull()
    }

    /** 补拉中的已读：对方已读 → 本人消息已读；本人其他端已读 → 未读重新计算 */
    @Test
    fun readMarkersInArchive() = runTest {
        val rig = rig()
        rig.db.syncStateDao().put(SyncStateEntity(MamSyncer.KEY_SELF, "a0"))
        archive.add(NOW, toBob("mine"))
        archive.add(NOW + 1, fromBob("in1"))
        archive.add(NOW + 2, fromBob("in2"))
        archive.add(
            NOW + 3,
            "<message xmlns='jabber:client' type='chat' id='d1' from='$BOB/pc' to='$SELF'>" +
                "<displayed xmlns='urn:xmpp:chat-markers:0' id='mine'/></message>",
        )
        archive.add(
            NOW + 4,
            "<message xmlns='jabber:client' type='chat' id='d2' from='$SELF/pc' to='$BOB'>" +
                "<displayed xmlns='urn:xmpp:chat-markers:0' id='in1'/></message>",
        )

        rig.syncer.syncSelf()

        assertThat(rig.db.messageDao().get(BOB, "mine")!!.status).isEqualTo(MessageEntity.STATUS_READ)
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(1)
    }

    /** 单轮页数上限：剩余的下次继续（从保存的游标之后） */
    @Test
    fun pageLimit_continuesNextRound() = runTest {
        val rig = rig(pageSize = 2, maxPages = 3)
        rig.db.syncStateDao().put(SyncStateEntity(MamSyncer.KEY_SELF, "a0"))
        repeat(10) { archive.add(NOW + it, fromBob("m$it")) }

        assertThat(rig.syncer.syncSelf()).isEqualTo(6)
        assertThat(archive.queries).hasSize(3)
        assertThat(rig.db.syncStateDao().get(MamSyncer.KEY_SELF)).isEqualTo("a6")

        assertThat(rig.syncer.syncSelf()).isEqualTo(4)
        assertThat(rig.db.messageDao().get(BOB, "m9")).isNotNull()
    }

    /** 服务器一直不返回 complete：拉到空页时停止 */
    @Test
    fun neverComplete_stopsOnEmptyPage() = runTest {
        val rig = rig()
        rig.db.syncStateDao().put(SyncStateEntity(MamSyncer.KEY_SELF, "a0"))
        repeat(3) { archive.add(NOW + it, fromBob("m$it")) }
        archive.neverComplete = true

        rig.syncer.syncSelf()

        assertThat(archive.queries.map { it.after }).containsExactly("a0", "a3").inOrder()
    }

    /** 4.11 历史：本地没有消息时取最新一页，之后从本地最早一条的存档 ID 往前翻，直到最早；只取与该会话的消息，不计未读 */
    @Test
    fun history_pagesBackward() = runTest {
        val rig = rig(pageSize = 2)
        repeat(5) { archive.add(NOW + it, fromBob("b$it")) }
        archive.add(NOW + 10, "<message xmlns='jabber:client' type='chat' id='other' from='10003@im.example.com/pc' to='$SELF'><body>x</body></message>")

        assertThat(rig.history.loadOlder(BOB)).isFalse()
        assertThat(archive.queries.last().before).isEqualTo("")
        assertThat(archive.queries.last().with).isEqualTo(BOB)
        assertThat(rig.db.messageDao().get(BOB, "b4")).isNotNull()
        assertThat(rig.db.messageDao().get(BOB, "b3")).isNotNull()

        assertThat(rig.history.loadOlder(BOB)).isFalse()
        assertThat(archive.queries.last().before).isEqualTo("a4")
        assertThat(rig.history.loadOlder(BOB)).isTrue()
        assertThat(rig.db.messageDao().get(BOB, "b0")).isNotNull()
        assertThat(rig.db.messageDao().get("10003@im.example.com", "other")).isNull()
        assertThat(rig.db.conversationDao().get(BOB)!!.unreadCount).isEqualTo(0)
        assertThat(client.sentMessages).isEmpty()
    }

    /** 本地最早一条没有存档 ID：用结束时间过滤 */
    @Test
    fun history_earliestWithoutArchiveId_usesEndTime() = runTest {
        val rig = rig()
        rig.db.messageDao().insert(
            MessageEntity(
                msgId = "local", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "text",
                body = "x", status = MessageEntity.STATUS_RECEIVED, serverTime = NOW,
            ),
        )
        archive.add(NOW - 1000, fromBob("older"))

        assertThat(rig.history.loadOlder(BOB)).isTrue()
        assertThat(archive.queries.last().end).isEqualTo(NOW)
        assertThat(archive.queries.last().before).isEqualTo("")
        assertThat(rig.db.messageDao().get(BOB, "older")).isNotNull()
    }

    /** 登录步骤：服务器不支持 MAM 时跳过 */
    @Test
    fun step_skipsWithoutMam() = runTest {
        val rig = rig()
        val step = MamSyncStep(rig.syncer)
        assertThat(step.runOnResume).isTrue()

        step.run(SessionContext("10001").apply { capabilities = ServerCapabilities(emptySet()) })
        assertThat(archive.queries).isEmpty()

        step.run(SessionContext("10001").apply { capabilities = ServerCapabilities(setOf(ServerCapabilities.MAM)) })
        assertThat(archive.queries).hasSize(1)
    }

    private companion object {
        const val SELF = "10001@im.example.com"
        const val BOB = "10002@im.example.com"
        const val NOW = 1_791_853_200_000L
        const val DAY = 24 * 3600 * 1000L
    }
}
