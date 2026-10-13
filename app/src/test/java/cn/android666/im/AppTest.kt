package cn.android666.im

import androidx.test.core.app.ApplicationProvider
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
}
