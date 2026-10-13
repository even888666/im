package cn.android666.im.testing

import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 集成测试使用的本地服务器（`server/up.sh` 部署在本机 Docker 中）。
 *
 * - 域名、测试账号密码从 `server/.env` 读取（目录由 Gradle 通过系统属性 `im.it.serverDir` 传入）；
 * - TLS 只信任 `server/certs/out/ca.crt`（开发环境根证书），并按域名校验主机名，与 App 的要求一致；
 * - 开发环境测试账号为 10001 ~ 10010（见 server/docker/openfire/entrypoint.sh）。
 */
object ItServer {

    /** 服务器部署目录 */
    private val serverDir: File by lazy {
        File(System.getProperty("im.it.serverDir") ?: error("缺少系统属性 im.it.serverDir，请通过 Gradle 运行测试"))
    }

    /** server/.env 中的配置 */
    private val env: Map<String, String> by lazy {
        val file = File(serverDir, ".env")
        check(file.exists()) { "未找到 ${file.path}，请先运行 server/up.sh 部署本地服务器" }
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /** XMPP 域名 */
    val domain: String by lazy { env["IM_DOMAIN"]?.takeIf { it.isNotBlank() } ?: "im.example.com" }

    /** 连接的主机地址：服务器部署在本机 */
    const val HOST = "127.0.0.1"

    /** 客户端端口 */
    const val PORT = 5222

    /** 测试账号的密码 */
    val testUserPassword: String by lazy { env["TEST_USER_PASSWORD"] ?: error(".env 中缺少 TEST_USER_PASSWORD") }

    /** 只信任开发根证书的 TrustManager */
    val trustManager: X509TrustManager by lazy {
        val caFile = File(serverDir, "certs/out/ca.crt")
        check(caFile.exists()) { "未找到根证书 ${caFile.path}，请先运行 server/up.sh" }
        val ca = caFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("im-root-ca", ca)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    /** 使用 [trustManager] 的 SSLContext，供 HTTP 上传 / 下载使用 */
    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    /**
     * 测试账号的工号。
     *
     * @param index 1 ~ 10
     * @return 工号，例如 index = 1 时为 10001
     */
    fun user(index: Int): String {
        require(index in 1..10) { "测试账号只有 10001 ~ 10010" }
        return (10_000 + index).toString()
    }

    /**
     * 工号对应的 bare JID。
     *
     * @param username 工号
     * @return 例如 10001@im.example.com
     */
    fun jid(username: String): EntityBareJid = JidCreate.entityBareFrom("$username@$domain")

    /**
     * 创建连接配置。
     *
     * @param username 工号
     * @param password 密码，默认测试账号密码
     * @param resource 端（resource），默认 android
     * @param securityMode TLS 模式，默认必须使用 TLS
     * @return 连接配置
     */
    fun config(
        username: String,
        password: String = testUserPassword,
        resource: String = "android",
        securityMode: SecurityMode = SecurityMode.required,
    ): XMPPTCPConnectionConfiguration = XMPPTCPConnectionConfiguration.builder()
        .setXmppDomain(domain)
        .setHostAddress(InetAddress.getByName(HOST))
        .setPort(PORT)
        .setUsernameAndPassword(username, password)
        .setResource(resource)
        .setSecurityMode(securityMode)
        .setCustomX509TrustManager(trustManager)
        .build()

    /**
     * 创建连接并登录。调用方负责在测试结束时 [XMPPTCPConnection.disconnect]。
     *
     * @param username 工号
     * @param resource 端，默认 android
     * @param password 密码，默认测试账号密码
     * @return 已登录的连接
     */
    fun login(username: String, resource: String = "android", password: String = testUserPassword): XMPPTCPConnection =
        XMPPTCPConnection(config(username, password, resource)).apply {
            // Smack 默认不请求流恢复，App 需要断线后 resume（协议规范 §2.2 第 2 步），测试也一样开启
            setUseStreamManagementResumption(true)
            connect()
            login()
        }
}
