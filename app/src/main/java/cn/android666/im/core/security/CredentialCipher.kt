package cn.android666.im.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
// 不用 java.util.Base64：它需要 API 26，本项目 minSdk 为 24；Kotlin 标准库的实现各版本都可用
import kotlin.io.encoding.Base64

/**
 * 凭证加解密（功能清单 §14"凭证安全存储"：密码不明文存储）。定义为接口，测试时可替换。
 */
interface CredentialCipher {

    /**
     * 加密。
     *
     * @param plain 明文
     * @return 密文（可安全保存的字符串）
     */
    fun encrypt(plain: String): String

    /**
     * 解密。
     *
     * @param encrypted [encrypt] 的结果
     * @return 明文
     * @throws GeneralSecurityException 密文被篡改、格式错误或密钥已失效
     */
    fun decrypt(encrypted: String): String
}

/**
 * AES-256-GCM 加密：每次加密使用随机 IV，结果为 Base64(IV + 密文 + 认证标签)。
 * GCM 自带完整性校验，密文被篡改时解密失败。
 *
 * 密钥来源与算法分开：生产环境使用 [AndroidKeyStoreKeyProvider]（密钥保存在 Android Keystore，
 * 无法导出）；单元测试使用软件生成的密钥验证算法本身。
 *
 * @property keyProvider 提供 AES 密钥
 */
class AesGcmCredentialCipher(private val keyProvider: () -> SecretKey) : CredentialCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 不指定 IV，由 Cipher 生成随机 IV（Android Keystore 的密钥要求由系统生成 IV）
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encode(byteArrayOf(iv.size.toByte()) + iv + sealed)
    }

    override fun decrypt(encrypted: String): String {
        val data = try {
            Base64.decode(encrypted)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("密文格式错误", e)
        }
        if (data.isEmpty()) throw GeneralSecurityException("密文为空")
        val ivLength = data[0].toInt()
        if (ivLength <= 0 || data.size <= 1 + ivLength) throw GeneralSecurityException("密文格式错误")
        val iv = data.copyOfRange(1, 1 + ivLength)
        val sealed = data.copyOfRange(1 + ivLength, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 认证标签长度（位） */
        const val TAG_BITS = 128
    }
}

/**
 * 从 Android Keystore 获取（不存在时生成）AES-256 密钥。密钥只存在于系统安全区域，应用也无法导出，
 * 卸载应用后随之删除。
 *
 * 云环境无法测试（Robolectric 不支持 AndroidKeyStore），需真机验证（开发计划 2.12）。
 *
 * @property alias 密钥别名
 */
class AndroidKeyStoreKeyProvider(private val alias: String = DEFAULT_ALIAS) : () -> SecretKey {

    @Synchronized
    override fun invoke(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "im_credential_key"
        const val KEY_SIZE = 256
    }
}
