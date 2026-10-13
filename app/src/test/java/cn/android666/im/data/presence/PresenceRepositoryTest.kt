package cn.android666.im.data.presence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.OwnPresence
import cn.android666.im.core.xmpp.extension.presence.PresenceQueryRequest
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [PresenceRepository] 测试：本人状态的保存与发送、同事状态查询的缓存与分批 */
@RunWith(RobolectricTestRunner::class)
class PresenceRepositoryTest {

    private val client = FakeXmppClient()
    private val clock = FakeClock()
    private val holder = UserDatabaseHolder {
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java).build()
    }
    private val repository = PresenceRepository(client, holder, clock)

    @Before
    fun setUp() {
        holder.open("10001")
        // 服务器应答：每个查询的 JID 都在线，状态 meeting
        client.iqResponder = { request ->
            request as PresenceQueryRequest
            "<presence-query xmlns='urn:android666:im:presence:1'>" +
                request.jids.joinToString("") {
                    "<item jid='$it' online='true'><resource name='android' show='dnd' code='meeting' status='开会'/></item>"
                } + "</presence-query>"
        }
    }

    @After
    fun tearDown() = holder.close()

    @Test
    fun myStatus_savedAndLoaded_sentWhenConnected() = runTest {
        assertThat(repository.loadMyPresence()).isEqualTo(OwnPresence.AVAILABLE)

        // 未连接：只保存不发送
        repository.setMyStatus(MyStatusSetting(MyStatus.MEETING, "  下午 3 点前在开会  "))
        assertThat(client.lastPresence).isNull()

        // 已连接：立即发送
        client.login(Credential("10001", "p"))
        repository.setMyStatus(MyStatusSetting(MyStatus.BUSINESS_TRIP, "上海"))
        assertThat(client.lastPresence).isEqualTo(OwnPresence(show = "away", status = "上海", code = "business-trip"))

        // 新的仓库实例从数据库读出（模拟进程重启后登录）
        val reloaded = PresenceRepository(client, holder, clock)
        assertThat(reloaded.loadMyPresence()).isEqualTo(OwnPresence(show = "away", status = "上海", code = "business-trip"))
        assertThat(reloaded.myStatus.value).isEqualTo(MyStatusSetting(MyStatus.BUSINESS_TRIP, "上海"))
    }

    @Test
    fun onlineStatus_noStateElement() {
        assertThat(MyStatusSetting(MyStatus.ONLINE, " ").toOwnPresence()).isEqualTo(OwnPresence.AVAILABLE)
    }

    @Test
    fun query_cachedFor60Seconds() = runTest {
        val first = repository.get("10002@im.example.com")!!
        assertThat(first.overall).isEqualTo(MyStatus.MEETING)
        assertThat(first.resources.single().text).isEqualTo("开会")

        repository.get("10002@im.example.com")
        assertThat(client.sentIqs).hasSize(1)

        clock.advanceBy(PresenceRepository.CACHE_MILLIS)
        repository.get("10002@im.example.com")
        assertThat(client.sentIqs).hasSize(2)

        repository.get("10002@im.example.com", forceRefresh = true)
        assertThat(client.sentIqs).hasSize(3)
    }

    /** 超过 100 个分批查询；已缓存的不再查询 */
    @Test
    fun query_batchesOf100() = runTest {
        repository.get("0@im.example.com")
        val jids = (0 until 150).map { "$it@im.example.com" }

        val result = repository.query(jids)

        assertThat(result).hasSize(150)
        val requests = client.sentIqs.filterIsInstance<PresenceQueryRequest>()
        assertThat(requests.map { it.jids.size }).containsExactly(1, 100, 49).inOrder()
    }

    @Test
    fun logout_clearsCacheAndStatus() = runTest {
        repository.get("10002@im.example.com")
        repository.setMyStatus(MyStatusSetting(MyStatus.BUSY))
        repository.onLogout()

        assertThat(repository.myStatus.value).isEqualTo(MyStatusSetting())
        repository.get("10002@im.example.com")
        assertThat(client.sentIqs).hasSize(2)
    }

    @Test
    fun encodeDecode() {
        val setting = MyStatusSetting(MyStatus.AWAY, "午休|稍后回复")
        assertThat(PresenceRepository.decode(PresenceRepository.encode(setting))).isEqualTo(setting)
        assertThat(PresenceRepository.decode(null)).isEqualTo(MyStatusSetting())
        assertThat(PresenceRepository.decode("UNKNOWN|x").status).isEqualTo(MyStatus.ONLINE)
    }
}
