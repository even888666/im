package cn.android666.im.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** [WatermarkView] 测试：真实绘制（NATIVE 图形模式），有文字时平铺满整个区域、颜色很淡，不拦截触摸 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkViewTest {

    private val view = WatermarkView(ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Im))

    /**
     * 绘制到位图。
     *
     * @return 400 × 600 的位图
     */
    private fun render(): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 400, 600)
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** 统计某个区域内非透明像素的数量与最大不透明度 */
    private fun stats(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
        var count = 0
        var maxAlpha = 0
        for (x in left until right) {
            for (y in top until bottom) {
                val alpha = bitmap.getPixel(x, y) ushr 24
                if (alpha > 0) count++
                maxAlpha = maxOf(maxAlpha, alpha)
            }
        }
        return count to maxAlpha
    }

    @Test
    fun noText_transparent() {
        val bitmap = render()
        assertThat(stats(bitmap, 0, 0, 400, 600).first).isEqualTo(0)
    }

    @Test
    fun text_tiledFaintly() {
        view.text = "张三 10001"
        val bitmap = render()

        // 四个角落都有水印（平铺覆盖整个区域）
        for ((l, t) in listOf(0 to 0, 200 to 0, 0 to 300, 200 to 300)) {
            assertThat(stats(bitmap, l, t, l + 200, t + 300).first).isGreaterThan(0)
        }
        // 很淡：不透明度不超过设定值
        assertThat(stats(bitmap, 0, 0, 400, 600).second).isAtMost(WatermarkView.ALPHA)
    }

    @Test
    fun notInteractive() {
        assertThat(view.isClickable).isFalse()
        assertThat(view.isFocusable).isFalse()
        assertThat(view.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    }

    @Test
    fun label() {
        assertThat(WatermarkView.label("张三", "10001")).isEqualTo("张三 10001")
        assertThat(WatermarkView.label(null, "10001")).isEqualTo("10001")
        assertThat(WatermarkView.label(" ", "10001")).isEqualTo("10001")
    }
}
