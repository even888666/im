package cn.android666.im.core.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [LogFilePrefixStore] 与 [LogFilePrefixManager] 的读写测试（真实 SharedPreferences） */
@RunWith(RobolectricTestRunner::class)
class LogFilePrefixStoreTest {

    private lateinit var store: LogFilePrefixStore

    @Before
    fun setUp() {
        store = LogFilePrefixStore.create(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun read_neverSaved_returnsNull() {
        assertThat(store.read()).isNull()
    }

    @Test
    fun save_thenRead_returnsEmpNo() {
        store.save("10086")
        assertThat(store.read()).isEqualTo("10086")
    }

    /** 新建实例也能读到，说明确实持久化了，而不是只在内存里 */
    @Test
    fun save_persistsAcrossInstances() {
        store.save("10086")
        val another = LogFilePrefixStore.create(ApplicationProvider.getApplicationContext<Context>())
        assertThat(another.read()).isEqualTo("10086")
    }

    @Test
    fun save_null_clears() {
        store.save("10086")
        store.save(null)
        assertThat(store.read()).isNull()
    }

    @Test
    fun manager_onLogin_appliesAndSavesPrefix() {
        val applied = mutableListOf<String>()
        val manager = LogFilePrefixManager(store) { applied += it }

        manager.onLogin("10087")

        assertThat(applied).containsExactly("10087")
        assertThat(store.read()).isEqualTo("10087")
    }

    @Test
    fun manager_onLogin_invalidEmpNo_appliesDefault() {
        val applied = mutableListOf<String>()
        val manager = LogFilePrefixManager(store) { applied += it }

        manager.onLogin("   ")

        assertThat(applied).containsExactly(LogFilePrefix.DEFAULT)
    }
}
