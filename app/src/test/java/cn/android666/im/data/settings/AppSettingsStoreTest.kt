package cn.android666.im.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [AppSettingsStore] 测试 */
class AppSettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun privacy_versioned() = runTest {
        val store = AppSettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tempFolder.root, "s.preferences_pb") })

        assertThat(store.isPrivacyAgreed(1)).isFalse()

        store.setPrivacyAgreed(1)
        assertThat(store.isPrivacyAgreed(1)).isTrue()
        // 隐私政策更新到第 2 版后需要重新同意
        assertThat(store.isPrivacyAgreed(2)).isFalse()
    }
}
