package cn.android666.im.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.android666.im.core.log.LogSanitizer
import cn.android666.im.core.log.Logs
import cn.android666.im.core.security.CredentialCipher
import cn.android666.im.core.xmpp.Credential
import kotlinx.coroutines.flow.first
import java.security.GeneralSecurityException

/**
 * 已保存的登录信息。
 *
 * @property username 上次登录的工号
 * @property password 解密后的密码；没有保存密码或无法解密（例如系统重置了 Keystore）时为 null，此时需要用户重新输入
 */
data class SavedLogin(val username: String, val password: String?) {

    /** 能否自动登录 */
    val canAutoLogin: Boolean get() = password != null

    /**
     * 转为登录凭证。
     *
     * @return 凭证；没有密码时返回 null
     */
    fun toCredential(): Credential? = password?.let { Credential(username, it) }

    /** 不输出密码 */
    override fun toString(): String = "SavedLogin(username=$username, password=${LogSanitizer.maskSecret(password)})"
}

/**
 * 登录凭证存储（Android工程架构.md §5.1：全局配置 DataStore）：工号明文保存（登录页回显），
 * 密码经 [CredentialCipher] 加密后保存（功能清单 §14）。
 *
 * @property dataStore 全局配置 DataStore
 * @property cipher 加解密
 */
class CredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: CredentialCipher,
) {

    private val log = Logs.file(TAG)

    /**
     * 保存凭证（登录成功后调用）。
     *
     * @param credential 凭证
     */
    suspend fun save(credential: Credential) {
        val encrypted = cipher.encrypt(credential.password)
        dataStore.edit {
            it[KEY_USERNAME] = credential.username
            it[KEY_PASSWORD] = encrypted
        }
        log.info("已保存凭证：工号={}", credential.username)
    }

    /**
     * 更新密码（改密成功后调用，协议规范 §3.2：必须用新密码更新本地凭证）。
     *
     * @param newPassword 新密码
     */
    suspend fun updatePassword(newPassword: String) {
        val encrypted = cipher.encrypt(newPassword)
        dataStore.edit { it[KEY_PASSWORD] = encrypted }
        log.info("已更新保存的密码")
    }

    /**
     * 读取已保存的登录信息。
     *
     * @return 登录信息；从未登录过时返回 null
     */
    suspend fun load(): SavedLogin? {
        val prefs = dataStore.data.first()
        val username = prefs[KEY_USERNAME] ?: return null
        val password = prefs[KEY_PASSWORD]?.let { encrypted ->
            try {
                cipher.decrypt(encrypted)
            } catch (e: GeneralSecurityException) {
                // 密钥失效（例如系统重置了 Keystore、恢复出厂设置后迁移数据）：只能让用户重新输入密码
                log.warn("无法解密保存的密码，需要重新登录：工号={}", username, e)
                null
            }
        }
        return SavedLogin(username, password)
    }

    /**
     * 清除密码，保留工号（退出登录、被禁用、认证失败时调用：不能再自动登录，但登录页仍回显工号）。
     */
    suspend fun clearPassword() {
        dataStore.edit { it.remove(KEY_PASSWORD) }
        log.info("已清除保存的密码")
    }

    private companion object {
        const val TAG = "CredentialStore"
        val KEY_USERNAME = stringPreferencesKey("login_username")
        val KEY_PASSWORD = stringPreferencesKey("login_password_encrypted")
    }
}
