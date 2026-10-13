package cn.android666.im.it

import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.extension.mam.MamQueryRequest
import cn.android666.im.core.xmpp.extension.msg.IncomingMessage
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.extension.msg.MessageEnvelope
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID

/**
 * MAM 集成测试（开发计划 4.10）：在本地 Openfire（Monitoring 插件）上查询本人归档。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*MamIT*'`
 *
 * 使用测试账号 10003、10004。
 */
class MamIT {

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

    private suspend fun login(index: Int): SmackXmppClient = SmackXmppClient(
        serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
        trustManager = ItServer.trustManager,
        dispatchers = dispatchers,
        clock = SystemTimeClock,
        logStanzas = { false },
    ).also {
        clients += it
        it.login(Credential(ItServer.user(index), ItServer.testUserPassword))
        it.sendPresence()
    }

    /** 查询本人归档：按时间过滤，结果按时间顺序推送，fin 带分页信息；最后一条的存档 ID 等于 fin 的 last */
    @Test
    fun selfArchive_query(): Unit = runBlocking {
        val a = login(3)
        val b = login(4)
        val jidB = "${ItServer.user(4)}@${ItServer.domain}"
        val since = System.currentTimeMillis() - 1_000
        val ids = (1..3).map { UUID.randomUUID().toString() }
        ids.forEachIndexed { i, id -> a.sendMessage(MessageCodec.chat(jidB, id, "存档 $i", MessageContent.Text), 15_000) }
        // Monitoring 插件异步写入存档
        delay(3_000)

        val results = Collections.synchronizedList(mutableListOf<IncomingMessage>())
        val queryId = UUID.randomUUID().toString()
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            b.incomingMessages.collect { xml ->
                val parsed = MessageCodec.parse(xml)
                if ((parsed.envelope as? MessageEnvelope.Mam)?.queryId == queryId) results += parsed
            }
        }
        val fin = b.sendIq(MamQueryRequest(queryId = queryId, start = since, max = 50))
        delay(1_000)
        job.cancel()

        println("MAM fin=$fin，结果 ${results.size} 条：" + results.map { (it.envelope as MessageEnvelope.Mam).stanzaId to it.message.msgId })
        assertThat(fin.complete).isTrue()
        val mine = results.filter { it.message.msgId in ids }
        assertThat(mine.map { it.message.msgId }).containsExactlyElementsIn(ids).inOrder()
        assertThat((results.last().envelope as MessageEnvelope.Mam).stanzaId).isEqualTo(fin.rsm!!.last)
        val first = mine.first()
        assertThat(first.message.from).startsWith("${ItServer.user(3)}@")
        assertThat((first.envelope as MessageEnvelope.Mam).stamp).isNotNull()
    }

    /** MamPager：按页查询，每页的结果完整（最后一条一定在内），游标翻页直到 complete */
    @Test
    fun pager_pagesUntilComplete(): Unit = runBlocking {
        val a = login(3)
        val b = login(4)
        val jidB = "${ItServer.user(4)}@${ItServer.domain}"
        val since = System.currentTimeMillis() - 1_000
        val ids = (1..5).map { UUID.randomUUID().toString() }
        ids.forEach { a.sendMessage(MessageCodec.chat(jidB, it, "分页", MessageContent.Text), 15_000) }
        delay(3_000)

        val pager = cn.android666.im.data.sync.MamPager(b)
        val received = mutableListOf<String?>()
        var after: String? = null
        var pages = 0
        while (pages < 10) {
            val page = pager.query(MamQueryRequest(queryId = UUID.randomUUID().toString(), start = since, max = 2, after = after))
            pages++
            received += page.items.map { it.message.msgId }
            page.fin.rsm?.last?.let { last -> assertThat((page.items.last().envelope as MessageEnvelope.Mam).stanzaId).isEqualTo(last) }
            after = page.fin.rsm?.last
            if (page.fin.complete) break
        }

        assertThat(received.filter { it in ids }).containsExactlyElementsIn(ids).inOrder()
        assertThat(pages).isAtLeast(3)
    }
}
