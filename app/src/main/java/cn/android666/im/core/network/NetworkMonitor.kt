package cn.android666.im.core.network

import kotlinx.coroutines.flow.StateFlow

/**
 * 网络状态。
 *
 * @property available 是否有可用的默认网络
 * @property networkId 当前默认网络的标识（切换 Wi-Fi / 移动网络 / 专网 APN 时变化）；无网络时为 null
 */
data class NetworkStatus(val available: Boolean, val networkId: String? = null) {
    companion object {
        /** 无网络 */
        val NONE = NetworkStatus(available = false)
    }
}

/**
 * 网络状态监听（功能清单 §3"断线自动重连"、§18.2"网络切换"）。
 * 定义为接口，测试时用假实现模拟断网、恢复、切换网络。
 */
interface NetworkMonitor {

    /** 当前网络状态 */
    val status: StateFlow<NetworkStatus>
}
