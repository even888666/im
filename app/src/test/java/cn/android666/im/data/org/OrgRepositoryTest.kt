package cn.android666.im.data.org

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.xmpp.ServerCapabilities
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.org.OrgSyncRequest
import cn.android666.im.data.session.SessionContext
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OrgRepository] 测试：用假服务器（[FakeXmppClient] 按请求的 version / after 返回分页）驱动同步，数据库为 Room 内存库。
 */
@RunWith(RobolectricTestRunner::class)
class OrgRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val client = FakeXmppClient()
    private val clock = FakeClock()
    private val holder = UserDatabaseHolder {
        Room.inMemoryDatabaseBuilder(context, ImDatabase::class.java).allowMainThreadQueries().build()
    }
    private val repository = OrgRepository(client, holder, clock)

    /**
     * 假服务器的分页数据：键为 after（第一页为 null），值为该页的 sync 结果 XML。
     */
    private val pages = mutableMapOf<String?, String>()

    @Before
    fun setUp() {
        holder.open("10001")
        client.iqResponder = { request ->
            when (request) {
                is OrgSyncRequest -> pages[request.after] ?: emptyPage(request.version)
                else -> null
            }
        }
    }

    @After
    fun tearDown() {
        holder.close()
    }

    /**
     * 生成一页同步结果。
     *
     * @param version 版本号
     * @param full 是否全量
     * @param items 部门 / 员工元素
     * @param last 本页最后一项的游标，null 表示没有 RSM
     * @return XML
     */
    private fun page(version: Long, full: Boolean, items: String, last: String?): String =
        "<sync xmlns='urn:android666:im:org:1' version='$version' full='$full'>$items" +
            (last?.let { "<set xmlns='http://jabber.org/protocol/rsm'><first>x</first><last>$it</last><count>9</count></set>" } ?: "") +
            "</sync>"

    /** 空页（最后一页） */
    private fun emptyPage(version: Long): String =
        "<sync xmlns='urn:android666:im:org:1' version='${version.coerceAtLeast(1)}' full='false'>" +
            "<set xmlns='http://jabber.org/protocol/rsm'><count>0</count></set></sync>"

    private fun dept(id: String, parent: String, name: String, op: String = "upsert") =
        "<department id='$id' parentId='$parent' name='$name' sort='10' memberCount='1' op='$op'/>"

    private fun emp(no: String, name: String, pinyin: String, initials: String, dept: String, mobile: String = "") =
        "<employee jid='$no@im.example.com' empNo='$no' name='$name' pinyin='$pinyin' initials='$initials' deptId='$dept' " +
            "title='' mobile='$mobile' phone='' email='' sort='0' avatarHash='' op='upsert'/>"

    /** 准备全量数据：两页 + 空页 */
    private fun fullData(version: Long = 20) {
        pages[null] = page(
            version, true,
            dept("d1", "", "总部") + dept("d2", "d1", "研发中心") + dept("d3", "d1", "上海分公司"),
            last = "d:d3",
        )
        pages["d:d3"] = page(
            version + 1, true,
            emp("10001", "张三", "zhangsan", "zs", "d2", "13800000001") + emp("10002", "李四", "lisi", "ls", "d3") +
                emp("10003", "张三丰", "zhangsanfeng", "zsf", "d2"),
            last = "e:10003",
        )
    }

    @Test
    fun fullSync_pagesWrittenAndFirstPageVersionSaved() = runTest {
        fullData()

        val result = repository.sync()

        assertThat(result).isEqualTo(OrgSyncResult(version = 20, full = true, pages = 3, upserts = 6, deletes = 0))
        val db = holder.require()
        assertThat(db.departmentDao().count()).isEqualTo(3)
        assertThat(db.employeeDao().count()).isEqualTo(3)
        assertThat(db.syncStateDao().get(OrgRepository.KEY_VERSION)).isEqualTo("20")
        // 请求带的是本地版本号（首次为 0）和上一页的游标
        val requests = client.sentIqs.filterIsInstance<OrgSyncRequest>()
        assertThat(requests.map { it.version }).containsExactly(0L, 0L, 0L)
        assertThat(requests.map { it.after }).containsExactly(null, "d:d3", "e:10003").inOrder()
    }

    @Test
    fun incrementalSync_upsertAndDelete() = runTest {
        fullData()
        repository.sync()
        pages.clear()
        pages[null] = page(
            25, false,
            dept("d3", "", "", op = "delete") + emp("10001", "张三", "zhangsan", "zs", "d1") +
                "<employee jid='10002@im.example.com' op='delete'/>",
            last = "e:10002",
        )

        val result = repository.sync()

        assertThat(result.full).isFalse()
        assertThat(result.upserts).isEqualTo(1)
        assertThat(result.deletes).isEqualTo(2)
        val db = holder.require()
        assertThat(db.departmentDao().get("d3")).isNull()
        assertThat(db.employeeDao().get("10002@im.example.com")).isNull()
        assertThat(db.employeeDao().get("10001@im.example.com")?.deptId).isEqualTo("d1")
        assertThat(client.sentIqs.filterIsInstance<OrgSyncRequest>().last().version).isEqualTo(20L)
        assertThat(db.syncStateDao().get(OrgRepository.KEY_VERSION)).isEqualTo("25")
    }

    /** 全量同步（服务器判定本地版本过旧）：先清空本地缓存 */
    @Test
    fun fullSync_clearsOldData() = runTest {
        fullData()
        repository.sync()
        pages.clear()
        pages[null] = page(90, true, dept("d9", "", "新总部") + emp("10009", "新人", "xinren", "xr", "d9"), last = null)

        repository.sync()

        val db = holder.require()
        assertThat(db.departmentDao().all().map { it.id }).containsExactly("d9")
        assertThat(db.employeeDao().count()).isEqualTo(1)
    }

    /** 中途失败：不写入任何数据，版本号不变 */
    @Test
    fun failureMidway_nothingWritten() = runTest {
        fullData()
        client.iqResponder = { request ->
            request as OrgSyncRequest
            if (request.after == "d:d3") throw XmppException.Timeout(null)
            pages[request.after]
        }

        assertThrows(XmppException.Timeout::class.java) { kotlinx.coroutines.runBlocking { repository.sync() } }

        val db = holder.require()
        assertThat(db.departmentDao().count()).isEqualTo(0)
        assertThat(db.syncStateDao().get(OrgRepository.KEY_VERSION)).isNull()
        assertThat(repository.syncing.value).isFalse()
    }

    /** 回到前台：10 分钟内不重复同步 */
    @Test
    fun syncIfStale_respectsInterval() = runTest {
        fullData()
        assertThat(repository.syncIfStale()).isTrue()
        clock.advanceBy(OrgRepository.STALE_AFTER_MILLIS - 1)
        assertThat(repository.syncIfStale()).isFalse()
        clock.advanceBy(1)
        assertThat(repository.syncIfStale()).isTrue()
    }

    /** 未登录（数据库未打开）时不同步 */
    @Test
    fun syncIfStale_loggedOut_skipped() = runTest {
        holder.close()
        assertThat(repository.syncIfStale()).isFalse()
        assertThat(client.sentIqs).isEmpty()
    }

    @Test
    fun search_rankingAndDepartmentPath() = runTest {
        fullData()
        repository.sync()

        assertThat(repository.search("张三").map { it.empNo }).containsExactly("10001", "10003").inOrder()
        assertThat(repository.search(" ZS ").map { it.empNo }).containsExactly("10001", "10003").inOrder()
        assertThat(repository.search("0001").map { it.empNo }).containsExactly("10001")
        // 上级部门名称匹配：总部下的所有人
        assertThat(repository.search("总部").map { it.empNo }).containsExactly("10001", "10002", "10003")
        assertThat(repository.search("上海").map { it.empNo }).containsExactly("10002")
        assertThat(repository.search("%")).isEmpty()
        assertThat(repository.search("")).isEmpty()
        assertThat(repository.search("张", limit = 1)).hasSize(1)
    }

    @Test
    fun queries_childrenMembersPath() = runTest {
        fullData()
        repository.sync()

        repository.observeChildren("d1").test {
            assertThat(awaitItem().map { it.name }).containsExactly("研发中心", "上海分公司")
            cancelAndIgnoreRemainingEvents()
        }
        repository.observeMembers("d2").test {
            assertThat(awaitItem().map { it.name }).containsExactly("张三", "张三丰").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repository.departmentPath("d2")).isEqualTo("总部/研发中心")
        assertThat(repository.departmentPath("nope")).isEqualTo("")
        assertThat(repository.employee("10001@im.example.com")?.mobile).isEqualTo("13800000001")
    }

    /** 登录步骤：服务器不支持组织架构时跳过 */
    @Test
    fun syncStep_skipsWithoutCapability() = runTest {
        fullData()
        val step = OrgSyncStep(repository)
        val context = SessionContext("10001")

        step.run(context)
        assertThat(client.sentIqs).isEmpty()

        context.capabilities = ServerCapabilities(setOf(ServerCapabilities.ORG))
        step.run(context)
        assertThat(holder.require().employeeDao().count()).isEqualTo(3)
    }

    @Test
    fun escapeLike() {
        assertThat(OrgRepository.escapeLike("a_b%c\\d")).isEqualTo("a\\_b\\%c\\\\d")
    }
}
