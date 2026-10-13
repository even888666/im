package cn.android666.im.core.common

import cn.android666.im.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/** 调度器提供者测试：生产实现对应 Dispatchers；测试实现能用虚拟时间控制注入的调度器 */
class DispatcherProviderTest {

    @Test
    fun default_mapsToKotlinDispatchers() {
        val provider = DefaultDispatcherProvider()
        assertThat(provider.io).isSameInstanceAs(Dispatchers.IO)
        assertThat(provider.default).isSameInstanceAs(Dispatchers.Default)
    }

    /** 被测代码切到注入的 io 调度器后，delay 仍由测试的虚拟时间控制 */
    @Test
    fun testProvider_virtualTimeControlsInjectedIo() {
        val provider = TestDispatcherProvider()
        runTest(provider.dispatcher) {
            var done = false
            launch {
                withContext(provider.io) { delay(30_000) }
                done = true
            }
            advanceTimeBy(29_999)
            assertThat(done).isFalse()
            advanceTimeBy(2)
            assertThat(done).isTrue()
        }
    }
}
