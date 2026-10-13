package cn.android666.im.ui.media

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.App
import cn.android666.im.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** 大图预览页的 Robolectric 测试：本地原图直接显示，可保存 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePreviewActivityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun localImage_readyAndSaveVisible() {
        val file = temp.newFile("a.jpg")
        file.outputStream().use { createBitmap(40, 30).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val intent = ImagePreviewActivity.intent(app, PreviewImage("", "a.jpg", "image/jpeg", null, file.path, null))
        ActivityScenario.launch<ImagePreviewActivity>(intent).use { scenario ->
            val deadline = System.currentTimeMillis() + 5_000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                scenario.onActivity { ready = it.findViewById<View>(R.id.save).isVisible }
                if (!ready) Thread.sleep(10)
            }
            assertThat(ready).isTrue()
            scenario.onActivity { assertThat(it.findViewById<View>(R.id.status).isVisible).isFalse() }
        }
    }

    /** 没有地址也没有本地文件：显示失败 */
    @Test
    fun nothing_failed() {
        val intent = ImagePreviewActivity.intent(app, PreviewImage("", "a.jpg", "image/jpeg", null, null, null))
        ActivityScenario.launch<ImagePreviewActivity>(intent).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity {
                assertThat(it.findViewById<android.widget.TextView>(R.id.status).text.toString()).isEqualTo(app.getString(R.string.preview_failed))
                assertThat(it.findViewById<View>(R.id.save).isVisible).isFalse()
            }
        }
    }
}
