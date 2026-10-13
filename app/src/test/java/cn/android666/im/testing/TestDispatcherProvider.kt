package cn.android666.im.testing

import cn.android666.im.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher

/**
 * 测试用调度器提供者：io / default / main 全部使用同一个 [TestDispatcher]，
 * 使所有协程在 `runTest` 的同一调度器上执行，虚拟时间可控。
 *
 * @property dispatcher 共享的测试调度器；与 `runTest(dispatcher)` 传入同一个实例
 */
class TestDispatcherProvider(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}
