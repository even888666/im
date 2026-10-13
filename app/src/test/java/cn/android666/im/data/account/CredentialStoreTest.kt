package cn.android666.im.data.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cn.android666.im.core.security.CredentialCipher
import cn.android666.im.core.xmpp.Credential
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException

/** [CredentialStore] 测试：使用真实的 DataStore（临时文件）和可逆的假加密 */
class CredentialStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 假加密：前缀 + 反转，能验证"保存的不是明文"且可逆 */
    private class FakeCipher : CredentialCipher {
        var failDecrypt = false
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(encrypted: String): String {
            if (failDecrypt) throw GeneralSecurityException("密钥失效")
            return encrypted.removePrefix("enc:").reversed()
        }
    }

    private val cipher = FakeCipher()

    /** 每个测试使用独立的 DataStore 文件 */
    private lateinit var file: File

    private fun TestScope.newStore(): CredentialStore {
        if (!::file.isInitialized) file = File(tempFolder.root, "settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        return CredentialStore(dataStore, cipher)
    }

    @Test
    fun neverSaved_loadNull() = runTest {
        assertThat(newStore().load()).isNull()
    }

    @Test
    fun save_thenLoad() = runTest {
        val store = newStore()

        store.save(Credential("10001", "Passw0rd"))

        val saved = store.load()!!
        assertThat(saved.username).isEqualTo("10001")
        assertThat(saved.password).isEqualTo("Passw0rd")
        assertThat(saved.canAutoLogin).isTrue()
        assertThat(saved.toCredential()!!.password).isEqualTo("Passw0rd")
    }

    /** 文件中保存的是密文，不是明文 */
    @Test
    fun save_passwordNotStoredInPlainText() = runTest {
        newStore().save(Credential("10001", "Passw0rd"))

        val bytes = file.readBytes().toString(Charsets.UTF_8)
        assertThat(bytes).contains("10001")
        assertThat(bytes).doesNotContain("Passw0rd")
        assertThat(bytes).contains("enc:")
    }

    @Test
    fun updatePassword_keepsUsername() = runTest {
        val store = newStore()
        store.save(Credential("10001", "old"))

        store.updatePassword("new")

        assertThat(store.load()).isEqualTo(SavedLogin("10001", "new"))
    }

    /** 退出登录后保留工号（登录页回显），不能再自动登录 */
    @Test
    fun clearPassword_keepsUsername() = runTest {
        val store = newStore()
        store.save(Credential("10001", "p"))

        store.clearPassword()

        val saved = store.load()!!
        assertThat(saved.username).isEqualTo("10001")
        assertThat(saved.password).isNull()
        assertThat(saved.canAutoLogin).isFalse()
        assertThat(saved.toCredential()).isNull()
    }

    /** 无法解密（密钥失效）时只返回工号，需要重新输入密码 */
    @Test
    fun decryptFailure_passwordNull() = runTest {
        val store = newStore()
        store.save(Credential("10001", "p"))
        cipher.failDecrypt = true

        assertThat(store.load()).isEqualTo(SavedLogin("10001", null))
    }

    @Test
    fun savedLogin_toStringHidesPassword() {
        assertThat(SavedLogin("10001", "secret").toString()).doesNotContain("secret")
    }
}
