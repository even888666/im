package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.extension.org.EmployeesRequest
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.data.avatar.AvatarRepository
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * 头像端到端测试（开发计划 3.21）：上传 vCard 头像后，org 插件更新员工的 avatarHash（与 XEP-0153 一致），
 * 其他人查询员工详情拿到新哈希，并能下载到同样的图片。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*AvatarIT*'`
 *
 * 使用测试账号 10018（上传）、10017（查看）。
 */
class AvatarIT {

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    private val clients = mutableListOf<SmackXmppClient>()

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { runCatching { it.logout() } }
    }

    /**
     * 登录。
     *
     * @param index 测试账号序号
     * @return 客户端
     */
    private suspend fun login(index: Int): SmackXmppClient = SmackXmppClient(
        serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
        trustManager = ItServer.trustManager,
        dispatchers = dispatchers,
        clock = SystemTimeClock,
        logStanzas = { false },
    ).also {
        clients += it
        it.login(Credential(ItServer.user(index), ItServer.testUserPassword))
    }

    @Test
    fun uploadAvatar_hashSyncedAndDownloadable() = runBlocking {
        val owner = login(18)
        val viewer = login(17)
        val jid = "${ItServer.user(18)}@${ItServer.domain}"
        // 每次运行用不同的数据，哈希一定变化
        val image = "假头像-${UUID.randomUUID()}".toByteArray()
        val expected = AvatarRepository.sha1(image)

        owner.setMyAvatar(image, "image/jpeg")

        var hash = ""
        for (i in 0 until 30) {
            hash = viewer.sendIq(EmployeesRequest(listOf(jid))).employees.single().avatarHash
            if (hash == expected) break
            delay(100)
        }
        assertThat(hash).isEqualTo(expected)
        assertThat(viewer.loadAvatar(jid)).isEqualTo(image)
        // 没有设置过头像的人
        assertThat(viewer.loadAvatar("${ItServer.user(19)}@${ItServer.domain}")).isNull()
    }
}
