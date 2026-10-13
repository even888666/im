package cn.android666.im.it

import cn.android666.im.core.network.HttpClients
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTP 集成测试（开发计划 5.1）：用全局 OkHttpClient（只信任企业根证书）下载本地文件上传服务中的文件；
 * 系统默认信任配置连接时证书校验失败。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*HttpIT*'`
 *
 * 使用测试账号 10007。
 */
class HttpIT {

    private val connections = mutableListOf<XMPPTCPConnection>()

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() {
        connections.forEach { runCatching { it.disconnect() } }
    }

    /** App 中的客户端：企业根证书 + 开发域名解析到本机 */
    private val client: OkHttpClient by lazy {
        HttpClients.create(ItServer.sslContext, ItServer.trustManager, ItServer.domain, ItServer.HOST)
    }

    @Test
    fun download_withRootCa() {
        val connection = ItServer.login(ItServer.user(7)).also { connections += it }
        val upload = HttpFileUploadManager.getInstanceFor(connection).apply { setTlsContext(ItServer.sslContext) }
        assertThat(upload.discoverUploadService()).isTrue()
        val content = "HTTP 下载 ${UUID.randomUUID()}"
        val file = File.createTempFile("http-it", ".txt").apply {
            writeText(content)
            deleteOnExit()
        }
        val url = upload.uploadFile(file).toString()

        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.body.string()).isEqualTo(content)
        }
        // 不存在的文件（槽位 ID 不存在）：404（界面提示"文件已过期或被清理"）。
        // 注意：上传服务按槽位 ID 定位文件，同一槽位下换文件名仍会返回该文件
        val missing = url.substringBefore("/httpfileupload/") + "/httpfileupload/${UUID.randomUUID()}/a.txt"
        client.newCall(Request.Builder().url(missing).build()).execute().use { response ->
            assertThat(response.code).isEqualTo(404)
        }
    }

    /** 不信任企业根证书的客户端无法连接（证明证书校验生效） */
    @Test
    fun systemTrust_rejected() {
        val systemTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(systemTrust), null) }
        val untrusted = HttpClients.create(sslContext, systemTrust, ItServer.domain, ItServer.HOST)

        assertThrows(SSLHandshakeException::class.java) {
            untrusted.newCall(Request.Builder().url("https://${ItServer.domain}:7443/").build()).execute().close()
        }
    }
}
