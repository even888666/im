package cn.android666.im.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.encoding.Base64

/**
 * [AesGcmCredentialCipher] 测试：用软件生成的 AES 密钥验证算法本身
 * （Android Keystore 中的密钥在 JVM 上无法使用，需真机验证）。
 */
class AesGcmCredentialCipherTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = AesGcmCredentialCipher { key }

    @Test
    fun encryptThenDecrypt_roundTrip() {
        val encrypted = cipher.encrypt("Passw0rd!中文")

        assertThat(encrypted).doesNotContain("Passw0rd")
        assertThat(cipher.decrypt(encrypted)).isEqualTo("Passw0rd!中文")
    }

    /** 每次加密使用随机 IV：同一明文得到不同密文 */
    @Test
    fun sameInput_differentCiphertext() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"))
    }

    /** GCM 完整性校验：密文被篡改时解密失败 */
    @Test
    fun tampered_rejected() {
        val bytes = Base64.decode(cipher.encrypt("secret"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()

        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(Base64.encode(bytes)) }
    }

    /** 换了密钥（例如 Keystore 被重置）无法解密 */
    @Test
    fun differentKey_rejected() {
        val encrypted = cipher.encrypt("secret")
        val other = AesGcmCredentialCipher { newKey() }

        assertThrows(GeneralSecurityException::class.java) { other.decrypt(encrypted) }
    }

    @Test
    fun malformed_rejected() {
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt("不是base64!!") }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt("") }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(Base64.encode(byteArrayOf(12, 1, 2))) }
    }

    @Test
    fun emptyPassword_roundTrip() {
        assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty()
    }
}
