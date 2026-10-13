package cn.android666.im.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** [AndroidNetworkMonitor] 测试：通过 Robolectric 触发系统的默认网络回调 */
@RunWith(RobolectricTestRunner::class)
class AndroidNetworkMonitorTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /** 被测监听器注册的回调（App 启动时也会创建一个监听器并注册回调，需排除） */
    private lateinit var callback: ConnectivityManager.NetworkCallback

    /**
     * 创建被测监听器，并找出它新注册的回调。
     *
     * @return 监听器
     */
    private fun newMonitor(): AndroidNetworkMonitor {
        val before = shadowOf(connectivityManager).networkCallbacks.toSet()
        val monitor = AndroidNetworkMonitor(context)
        callback = (shadowOf(connectivityManager).networkCallbacks - before).single()
        return monitor
    }

    /** 被测监听器的回调 */
    private fun callback(): ConnectivityManager.NetworkCallback = callback

    /**
     * 构造一个带 INTERNET 能力的网络能力对象。
     *
     * @param internet 是否有 INTERNET 能力
     */
    private fun capabilities(internet: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            if (internet) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    @Test
    fun onAvailable_thenOnLost_updatesStatus() {
        val monitor = newMonitor()
        val wifi: Network = ShadowNetwork.newInstance(100)

        callback().onAvailable(wifi)
        assertThat(monitor.status.value).isEqualTo(NetworkStatus(available = true, networkId = wifi.toString()))

        callback().onLost(wifi)
        assertThat(monitor.status.value).isEqualTo(NetworkStatus.NONE)
    }

    /** 切换网络时：先收到新网络可用、再收到旧网络丢失，状态应保持为新网络 */
    @Test
    fun switchNetwork_lostOfOldNetworkIgnored() {
        val monitor = newMonitor()
        val wifi: Network = ShadowNetwork.newInstance(100)
        val mobile: Network = ShadowNetwork.newInstance(200)

        callback().onAvailable(wifi)
        callback().onAvailable(mobile)
        callback().onLost(wifi)

        assertThat(monitor.status.value).isEqualTo(NetworkStatus(available = true, networkId = mobile.toString()))
    }

    /** 网络失去 INTERNET 能力时视为不可用，恢复后重新可用 */
    @Test
    fun capabilitiesChanged_updatesAvailability() {
        val monitor = newMonitor()
        val wifi: Network = ShadowNetwork.newInstance(100)
        callback().onAvailable(wifi)

        callback().onCapabilitiesChanged(wifi, capabilities(internet = false))
        assertThat(monitor.status.value.available).isFalse()

        callback().onCapabilitiesChanged(wifi, capabilities(internet = true))
        assertThat(monitor.status.value.available).isTrue()
    }
}
