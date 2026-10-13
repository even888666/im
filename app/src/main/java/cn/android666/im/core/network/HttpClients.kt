package cn.android666.im.core.network

import cn.android666.im.core.log.Logs
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 全局 OkHttpClient 的创建（开发计划 5.1）：文件上传 / 下载、Coil 加载网络图片共用。
 *
 * - TLS 只信任企业根证书，与 XMPP 连接相同（协议规范 §9"证书"）；主机名按证书校验（OkHttp 默认）；
 * - [hostOverride] 不为空时（开发环境、专网没有 DNS），把 IM 域名及其子域名解析到该地址，其余域名正常解析；
 * - 每个请求记录一条日志（方法、地址的路径部分、状态码、耗时、大小），不记录内容。
 */
object HttpClients {

    private val log = Logs.file("Http")

    /** 连接超时（秒） */
    private const val CONNECT_TIMEOUT_SECONDS = 15L

    /** 读写超时（秒）：大文件上传 / 下载时按数据流动计时，不是总时长 */
    private const val IO_TIMEOUT_SECONDS = 60L

    /**
     * 创建客户端。
     *
     * @param sslContext 只信任企业根证书的 SSLContext（core.security.RootCaTrust）
     * @param trustManager 与 [sslContext] 对应的 TrustManager
     * @param domain IM 域名（如 im.example.com）
     * @param hostOverride 域名解析覆盖的地址（IP 或主机名）；为 null 时使用系统 DNS
     * @return OkHttpClient
     */
    fun create(sslContext: SSLContext, trustManager: X509TrustManager, domain: String, hostOverride: String?): OkHttpClient =
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .dns(OverrideDns(domain, hostOverride))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(LoggingInterceptor)
            .build()

    /**
     * 域名解析：IM 域名及其子域名解析到 [hostOverride]，其余交给系统 DNS。
     *
     * @property domain IM 域名
     * @property hostOverride 覆盖的地址；为 null 时全部使用系统 DNS
     */
    class OverrideDns(private val domain: String, private val hostOverride: String?) : Dns {

        /**
         * 解析。
         *
         * @param hostname 主机名
         * @return 地址列表
         */
        override fun lookup(hostname: String): List<InetAddress> {
            val override = hostOverride
            if (override != null && (hostname.equals(domain, ignoreCase = true) || hostname.endsWith(".$domain", ignoreCase = true))) {
                return InetAddress.getAllByName(override).toList()
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** 每个请求记录一条日志（不记录查询参数和内容，下载地址中的随机路径只记录文件名部分） */
    private object LoggingInterceptor : Interceptor {

        /**
         * 执行请求并记录。
         *
         * @param chain 拦截链
         * @return 响应
         */
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val target = "${request.url.host}/…/${request.url.pathSegments.lastOrNull().orEmpty()}"
            val start = System.nanoTime()
            return try {
                chain.proceed(request).also { response ->
                    log.info(
                        "{} {} → {}，{}ms，{} 字节",
                        request.method,
                        target,
                        response.code,
                        (System.nanoTime() - start) / 1_000_000,
                        response.body.contentLength(),
                    )
                }
            } catch (e: java.io.IOException) {
                log.warn("{} {} 失败，{}ms：{}", request.method, target, (System.nanoTime() - start) / 1_000_000, e.toString())
                throw e
            }
        }
    }
}
