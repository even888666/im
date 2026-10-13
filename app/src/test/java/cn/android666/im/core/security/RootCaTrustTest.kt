package cn.android666.im.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * [RootCaTrust] 测试：只信任指定根证书签发的服务器证书（测试证书见 src/test/resources/tls/README.md）。
 */
class RootCaTrustTest {

    /**
     * 读取测试资源中的证书。
     *
     * @param name 资源文件名
     * @return 证书
     */
    private fun cert(name: String): X509Certificate =
        javaClass.getResourceAsStream("/tls/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun trust() = RootCaTrust(javaClass.getResourceAsStream("/tls/test-ca.crt")!!)

    @Test
    fun loadsRootCertificate() {
        val trust = trust()

        assertThat(trust.certificates).hasSize(1)
        assertThat(trust.certificates.single().subjectX500Principal.name).contains("IM Test Root CA")
        assertThat(trust.trustManager.acceptedIssuers.map { it.subjectX500Principal })
            .containsExactly(trust.certificates.single().subjectX500Principal)
    }

    /** 由企业根证书签发的服务器证书通过校验 */
    @Test
    fun serverCertSignedByRootCa_trusted() {
        trust().trustManager.checkServerTrusted(arrayOf(cert("test-server.crt")), "RSA")
    }

    /** 由其他 CA 签发的证书被拒绝（即使主机名一样） */
    @Test
    fun serverCertSignedByOtherCa_rejected() {
        assertThrows(CertificateException::class.java) {
            trust().trustManager.checkServerTrusted(arrayOf(cert("untrusted-server.crt")), "RSA")
        }
    }

    /** 系统内置的公共 CA 也不被信任：只信任企业根证书 */
    @Test
    fun onlyRootCaIsAccepted() {
        assertThat(trust().trustManager.acceptedIssuers).hasLength(1)
    }

    @Test
    fun sslContext_isInitialized() {
        assertThat(trust().sslContext.socketFactory).isNotNull()
    }

    @Test
    fun emptyInput_rejected() {
        assertThrows(Exception::class.java) { RootCaTrust(ByteArray(0).inputStream()) }
    }
}
