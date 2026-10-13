package cn.android666.im.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import cn.android666.im.core.log.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 基于 [ConnectivityManager.registerDefaultNetworkCallback]（API 24 起可用）的网络状态监听。
 *
 * 只关心"默认网络"：系统在 Wi-Fi、移动网络、企业专网 APN 之间切换时，默认网络会变化，
 * 旧网络上的 TCP 连接通常已经失效，XmppConnectionManager 据此尽快检测并重连。
 *
 * 网络变化事件不频繁（只在切换时发生），逐条记录日志。
 *
 * @param context 任意 Context
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val log = Logs.file(TAG)

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val state = MutableStateFlow(currentStatus())
    override val status: StateFlow<NetworkStatus> = state.asStateFlow()

    init {
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                update(NetworkStatus(available = true, networkId = network.toString()), "可用")
            }

            override fun onLost(network: Network) {
                // 只有丢失的是当前默认网络时才视为断网（切换时可能先收到新网络 onAvailable）
                if (state.value.networkId == network.toString()) {
                    update(NetworkStatus.NONE, "丢失")
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (!usable && state.value.networkId == network.toString()) {
                    update(NetworkStatus.NONE, "不可用")
                } else if (usable && !state.value.available) {
                    update(NetworkStatus(available = true, networkId = network.toString()), "恢复可用")
                }
            }
        })
        log.info("开始监听网络，当前：{}", state.value)
    }

    /**
     * 更新网络状态并记录日志。
     *
     * @param newStatus 新状态
     * @param event 事件描述
     */
    private fun update(newStatus: NetworkStatus, event: String) {
        if (state.value == newStatus) return
        log.info("网络{}：{} -> {}", event, state.value, newStatus)
        state.value = newStatus
    }

    /**
     * 读取当前的默认网络状态（注册回调前的初始值）。
     *
     * @return 网络状态
     */
    private fun currentStatus(): NetworkStatus {
        val network = connectivityManager.activeNetwork ?: return NetworkStatus.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkStatus.NONE
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            NetworkStatus(available = true, networkId = network.toString())
        } else {
            NetworkStatus.NONE
        }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
