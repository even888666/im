package cn.android666.im.ui.main

import androidx.annotation.StringRes
import cn.android666.im.R
import cn.android666.im.core.network.NetworkStatus
import cn.android666.im.core.xmpp.ConnectionState

/**
 * 主界面顶部的连接状态条（功能清单 §3"连接状态提示"）。纯逻辑，便于单元测试。
 */
object ConnectionBanner {

    /**
     * 根据连接状态和网络状态决定提示文字。
     *
     * @param connection 连接状态
     * @param network 网络状态
     * @return 字符串资源 ID；已连接时返回 null（不显示状态条）
     */
    @StringRes
    fun textFor(connection: ConnectionState, network: NetworkStatus): Int? = when {
        connection is ConnectionState.Authenticated -> null
        !network.available -> R.string.banner_no_network
        connection is ConnectionState.Connecting -> R.string.banner_connecting
        connection is ConnectionState.Idle -> R.string.banner_connecting
        else -> R.string.banner_disconnected
    }
}
