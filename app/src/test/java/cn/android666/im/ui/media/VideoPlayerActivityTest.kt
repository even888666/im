package cn.android666.im.ui.media

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.App
import cn.android666.im.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 视频播放页的 Robolectric 测试：没有地址也没有本地文件时显示失败，不创建播放器 */
@RunWith(RobolectricTestRunner::class)
class VideoPlayerActivityTest {

    private val app = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun nothing_failed() {
        val intent = VideoPlayerActivity.intent(app, PreviewMedia("", "v.mp4", "video/mp4", null, null, null))
        ActivityScenario.launch<VideoPlayerActivity>(intent).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity {
                assertThat(it.findViewById<TextView>(R.id.status).text.toString()).isEqualTo(app.getString(R.string.preview_failed))
                assertThat(it.findViewById<View>(R.id.player).isVisible).isFalse()
                it.findViewById<View>(R.id.close).performClick()
                assertThat(it.isFinishing).isTrue()
            }
        }
    }
}
