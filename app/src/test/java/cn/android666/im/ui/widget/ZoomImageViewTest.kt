package cn.android666.im.ui.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ZoomImageView.correction] 测试：拖动范围限制 */
class ZoomImageViewTest {

    /** 图片比控件小：居中 */
    @Test
    fun smaller_centered() {
        assertThat(ZoomImageView.correction(0f, 100f, 300f)).isEqualTo(100f)
        assertThat(ZoomImageView.correction(150f, 250f, 300f)).isEqualTo(-50f)
    }

    /** 图片比控件大：左边露出空白时左移，右边露出空白时右移，不露空白时不动 */
    @Test
    fun larger_noGap() {
        assertThat(ZoomImageView.correction(20f, 520f, 300f)).isEqualTo(-20f)
        assertThat(ZoomImageView.correction(-300f, 200f, 300f)).isEqualTo(100f)
        assertThat(ZoomImageView.correction(-100f, 400f, 300f)).isEqualTo(0f)
    }
}
