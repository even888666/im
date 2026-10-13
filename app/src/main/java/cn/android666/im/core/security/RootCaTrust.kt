package cn.android666.im.core.security

import android.content.Context
import cn.android666.im.R
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 只信任企业根证书的 TLS 配置（功能清单 §18.2"自签证书方案"、协议规范 §2.1）。
 *
 * XMPP 连接（Smack）、文件上传 / 下载与官网 API（OkHttp）统一使用这里的 [trustManager] 和 [sslContext]，
 * 与 `res/xml/network_security_config.xml` 的信任范围一致：只信任企业根证书，不信任系统内置 CA、
 * 不信任用户手动安装的证书。**禁止**跳过证书校验或主机名校验。
 *
 * 根证书在构建时打包为 `res/raw/im_root_ca`（来源见 app/build.gradle.kts 中的 prepareRootCa 任务）。
 *
 * @param caPem 根证书（PEM 或 DER 格式，可包含多张证书，例如新旧根证书过渡期）
 */
class RootCaTrust(caPem: InputStream) {

    /** 受信任的根证书 */
    val certificates: List<X509Certificate> = caPem.use { input ->
        CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }
    }.also { require(it.isNotEmpty()) { "根证书文件中没有证书" } }

    /** 只信任 [certificates] 的 TrustManager */
    val trustManager: X509TrustManager = run {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, cert -> setCertificateEntry("im-root-ca-$index", cert) }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    /** 使用 [trustManager] 的 SSLContext（TLS 版本由系统协商，Android 7 起支持 TLS 1.2） */
    val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }

    companion object {
        /**
         * 从打包在 App 中的 `res/raw/im_root_ca` 加载。
         *
         * @param context 任意 Context
         * @return 信任配置
         */
        fun fromResources(context: Context): RootCaTrust =
            RootCaTrust(context.resources.openRawResource(R.raw.im_root_ca))
    }
}
