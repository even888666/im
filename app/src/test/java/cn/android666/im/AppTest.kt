package cn.android666.im

import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.xmpp.ConnectionState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.isActive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [App] 启动测试：清单中注册了 App，启动后依赖容器可用 */
@RunWith(RobolectricTestRunner::class)
class AppTest {

    @Test
    fun onCreate_createsContainer() {
        val app = ApplicationProvider.getApplicationContext<App>()

        val container = app.container
        assertThat(container.appContext).isSameInstanceAs(app)
        assertThat(container.applicationScope.isActive).isTrue()
        assertThat(container.serverClock.isCalibrated).isFalse()
        assertThat(container.idGenerator.messageId()).isNotEmpty()
    }

    /** XMPP 客户端可以在 Android 环境中创建（含 Smack 的 Android 初始化），初始为未连接 */
    @Test
    fun xmppClient_createdIdle() {
        val container = ApplicationProvider.getApplicationContext<App>().container

        assertThat(container.serverConfig.domain).isEqualTo("im.example.com")
        assertThat(container.serverConfig.resource).isEqualTo("android")
        assertThat(container.xmppClient.connectionState.value).isEqualTo(ConnectionState.Idle)
        assertThat(container.rootCaTrust.certificates).isNotEmpty()
    }
}
