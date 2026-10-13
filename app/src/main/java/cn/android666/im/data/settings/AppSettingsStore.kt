package cn.android666.im.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import cn.android666.im.core.log.Logs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 设备级设置（全局 DataStore）：隐私政策的同意状态、语音听筒播放；之后的设备级设置也放这里。
 *
 * @property dataStore 全局配置 DataStore
 */
class AppSettingsStore(private val dataStore: DataStore<Preferences>) {

    private val log = Logs.file(TAG)

    /**
     * 用户是否已同意当前版本的隐私政策（功能清单 §14：首次启动弹窗同意，同意前不采集信息）。
     * 隐私政策更新（版本号增大）后需要重新同意。
     *
     * @param currentVersion 当前隐私政策版本
     * @return 已同意当前或更新的版本时返回 true
     */
    suspend fun isPrivacyAgreed(currentVersion: Int): Boolean =
        (dataStore.data.first()[KEY_PRIVACY_VERSION] ?: 0) >= currentVersion

    /**
     * 记录用户同意了隐私政策。
     *
     * @param version 同意的版本
     */
    suspend fun setPrivacyAgreed(version: Int) {
        dataStore.edit { it[KEY_PRIVACY_VERSION] = version }
        log.info("用户同意隐私政策，版本 {}", version)
    }

    /** 语音消息是否用听筒播放（开发计划 5.5，默认扬声器） */
    val voiceEarpiece: Flow<Boolean> = dataStore.data.map { it[KEY_VOICE_EARPIECE] ?: false }

    /**
     * 设置语音消息的播放方式。
     *
     * @param earpiece true 为听筒，false 为扬声器
     */
    suspend fun setVoiceEarpiece(earpiece: Boolean) {
        dataStore.edit { it[KEY_VOICE_EARPIECE] = earpiece }
        log.info("语音播放方式：{}", if (earpiece) "听筒" else "扬声器")
    }

    private companion object {
        const val TAG = "AppSettings"
        val KEY_PRIVACY_VERSION = intPreferencesKey("privacy_agreed_version")
        val KEY_VOICE_EARPIECE = booleanPreferencesKey("voice_earpiece")
    }
}
