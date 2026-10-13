package cn.android666.im.it

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 组织架构同步端到端测试（开发计划 3.14、3.15）：真实服务器 + 真实 Room 数据库（Robolectric 内存库），
 * 验证全量同步写库、再次同步为增量、本地搜索结果与服务器测试数据一致。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*OrgSyncIT*'`
 *
 * 使用测试账号 10012。
 */
@RunWith(RobolectricTestRunner::class)
class OrgSyncIT {

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    private lateinit var client: SmackXmppClient
    private lateinit var holder: UserDatabaseHolder

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
        val context = ApplicationProvider.getApplicationContext<Context>()
        holder = UserDatabaseHolder { Room.inMemoryDatabaseBuilder(context, ImDatabase::class.java).build() }
        holder.open(ItServer.user(12))
        client = SmackXmppClient(
            serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
            trustManager = ItServer.trustManager,
            dispatchers = dispatchers,
            clock = SystemTimeClock,
            logStanzas = { false },
        )
    }

    @After
    fun tearDown() = runBlocking {
        if (::client.isInitialized) runCatching { client.logout() }
        if (::holder.isInitialized) holder.close()
    }

    @Test
    fun fullThenIncremental_localSearch() = runBlocking {
        client.login(Credential(ItServer.user(12), ItServer.testUserPassword))
        val repository = OrgRepository(client, holder, SystemTimeClock)

        val full = repository.sync()
        assertThat(full.full).isTrue()
        assertThat(full.upserts).isAtLeast(30)

        val incremental = repository.sync()
        assertThat(incremental.full).isFalse()
        assertThat(incremental.version).isAtLeast(full.version)

        assertThat(repository.search("zxm").map { it.name }).contains("曾小明")
        assertThat(repository.search("上海分公司").map { it.empNo }).containsExactly(ItServer.user(16), ItServer.user(17))
        val zhang = repository.search("张三").first()
        assertThat(repository.departmentPath(zhang.deptId)).isEqualTo("总部/研发中心/Android组")
    }
}
