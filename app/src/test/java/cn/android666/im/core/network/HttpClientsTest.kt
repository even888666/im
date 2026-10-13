package cn.android666.im.core.network

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** [HttpClients] 测试：IM 域名解析覆盖、请求可达（HTTPS 证书校验见 HttpIT） */
class HttpClientsTest {

    private val server = MockWebServer()

    /** 系统默认的信任配置（本测试只用 HTTP） */
    private val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as java.security.KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    private val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun overrideDns_domainAndSubdomains() {
        val dns = HttpClients.OverrideDns("im.example.com", "127.0.0.2")

        assertThat(dns.lookup("im.example.com").map { it.hostAddress }).containsExactly("127.0.0.2")
        assertThat(dns.lookup("IM.EXAMPLE.COM").map { it.hostAddress }).containsExactly("127.0.0.2")
        assertThat(dns.lookup("httpfileupload.im.example.com").map { it.hostAddress }).containsExactly("127.0.0.2")
        // 其他域名走系统解析
        assertThat(dns.lookup("localhost").first().isLoopbackAddress).isTrue()
        // 只是后缀相同的其他域名不覆盖（解析失败或解析到别的地址都可以）
        val other = runCatching { dns.lookup("evilim.example.com") }.getOrNull().orEmpty()
        assertThat(other.map { it.hostAddress }).doesNotContain("127.0.0.2")
    }

    @Test
    fun noOverride_systemDns() {
        assertThat(HttpClients.OverrideDns("localhost", null).lookup("localhost").first().isLoopbackAddress).isTrue()
    }

    /** 请求 IM 域名时连接到覆盖的地址 */
    @Test
    fun request_reachesOverriddenHost() {
        server.enqueue(MockResponse.Builder().body("hello").build())
        val client = HttpClients.create(sslContext, trustManager, "im.example.com", "127.0.0.1")

        val response = client.newCall(Request.Builder().url("http://im.example.com:${server.port}/files/a.txt").build()).execute()

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body.string()).isEqualTo("hello")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/files/a.txt")
    }
}
