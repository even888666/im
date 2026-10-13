package cn.android666.im

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric 冒烟测试：验证 JVM 上能拿到应用 Context 并读取合并后的资源。
 */
@RunWith(RobolectricTestRunner::class)
class RobolectricSmokeTest {

    /** 应用包名正确，说明 Robolectric 加载了本工程的清单 */
    @Test
    fun applicationContext_hasAppPackageName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context.packageName).isEqualTo("cn.android666.im")
    }

    /** 能读取 strings.xml 中的应用名，说明 isIncludeAndroidResources 生效 */
    @Test
    fun resources_areAvailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context.getString(R.string.app_name)).isNotEmpty()
    }
}
