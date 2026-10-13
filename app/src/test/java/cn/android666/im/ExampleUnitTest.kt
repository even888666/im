package cn.android666.im

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 *
 * 同时作为测试体系的冒烟测试：验证 JUnit、Truth、kotlinx-coroutines-test（虚拟时间）、Turbine 均可用。
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    /** 验证 runTest 的虚拟时间：delay 不真正等待，但 currentTime 前进 */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun runTest_virtualTime_advancesWithoutWaiting() = runTest {
        delay(60_000)
        assertThat(currentTime).isEqualTo(60_000)
    }

    /** 验证 Turbine 按顺序收集 Flow 的值并检测结束 */
    @Test
    fun turbine_collectsFlowValuesInOrder() = runTest {
        flow {
            emit(1)
            delay(1_000)
            emit(2)
        }.test {
            assertThat(awaitItem()).isEqualTo(1)
            assertThat(awaitItem()).isEqualTo(2)
            awaitComplete()
        }
    }
}
