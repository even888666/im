package cn.android666.im.testing

import cn.android666.im.core.network.NetworkMonitor
import cn.android666.im.core.network.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [NetworkMonitor] 的测试替身：直接修改 [status] 模拟断网、恢复、切换网络。
 *
 * @param initial 初始状态，默认有网络
 */
class FakeNetworkMonitor(initial: NetworkStatus = NetworkStatus(available = true, networkId = "wifi")) : NetworkMonitor {
    override val status = MutableStateFlow(initial)
}
