package cn.android666.im.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit 规则：测试期间把 [Dispatchers.Main] 替换为 [TestDispatcher]，测试结束后恢复。
 *
 * ViewModel 中的 `viewModelScope` 默认使用 Main 调度器，JVM 单元测试中没有 Android 主线程，
 * 必须替换后才能运行。用法：
 * ```
 * @get:Rule val mainDispatcherRule = MainDispatcherRule()
 * ```
 *
 * @property testDispatcher 替换 Main 的测试调度器，默认 [StandardTestDispatcher]（需手动推进），
 * 测试中可通过 `runTest(mainDispatcherRule.testDispatcher)` 共享同一个调度器以控制虚拟时间
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    /**
     * 测试开始前替换 Main 调度器。
     *
     * @param description 当前测试的描述信息（未使用）
     */
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    /**
     * 测试结束后恢复 Main 调度器，避免影响其他测试。
     *
     * @param description 当前测试的描述信息（未使用）
     */
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
