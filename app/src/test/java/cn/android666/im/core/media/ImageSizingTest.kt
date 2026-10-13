package cn.android666.im.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** [ImageSizing] 测试：压缩尺寸、解码倍数、旋转、气泡显示尺寸 */
class ImageSizingTest {

    @Test
    fun fit_scalesLongSideDown() {
        assertThat(ImageSizing.fit(4000, 3000, 1920)).isEqualTo(Dimension(1920, 1440))
        assertThat(ImageSizing.fit(1080, 2400, 1920)).isEqualTo(Dimension(864, 1920))
    }

    @Test
    fun fit_smallImageUnchanged() {
        assertThat(ImageSizing.fit(800, 600, 1920)).isEqualTo(Dimension(800, 600))
        assertThat(ImageSizing.fit(1920, 1920, 1920)).isEqualTo(Dimension(1920, 1920))
    }

    /** 极端长宽比时短边至少 1 像素 */
    @Test
    fun fit_extremeRatio_atLeastOnePixel() {
        assertThat(ImageSizing.fit(10000, 1, 320)).isEqualTo(Dimension(320, 1))
    }

    @Test
    fun fit_invalidSize_throws() {
        assertThrows(IllegalArgumentException::class.java) { ImageSizing.fit(0, 100, 320) }
    }

    @Test
    fun sampleSize_largestPowerOfTwoKeepingTarget() {
        assertThat(ImageSizing.sampleSize(4000, 3000, 1920)).isEqualTo(2)
        assertThat(ImageSizing.sampleSize(8000, 6000, 1920)).isEqualTo(4)
        assertThat(ImageSizing.sampleSize(3839, 100, 1920)).isEqualTo(1)
        assertThat(ImageSizing.sampleSize(3840, 100, 1920)).isEqualTo(2)
        assertThat(ImageSizing.sampleSize(100, 100, 320)).isEqualTo(1)
    }

    @Test
    fun rotated_swapsFor90And270() {
        assertThat(ImageSizing.rotated(400, 300, 0)).isEqualTo(Dimension(400, 300))
        assertThat(ImageSizing.rotated(400, 300, 90)).isEqualTo(Dimension(300, 400))
        assertThat(ImageSizing.rotated(400, 300, 180)).isEqualTo(Dimension(400, 300))
        assertThat(ImageSizing.rotated(400, 300, 270)).isEqualTo(Dimension(300, 400))
    }

    @Test
    fun displaySize_landscapeAndPortrait() {
        assertThat(ImageSizing.displaySize(320, 180, 160, 60)).isEqualTo(Dimension(160, 90))
        assertThat(ImageSizing.displaySize(180, 320, 160, 60)).isEqualTo(Dimension(90, 160))
    }

    /** 小图按原尺寸显示，但不小于下限 */
    @Test
    fun displaySize_smallImage() {
        assertThat(ImageSizing.displaySize(100, 80, 160, 60)).isEqualTo(Dimension(100, 80))
        assertThat(ImageSizing.displaySize(20, 10, 160, 60)).isEqualTo(Dimension(60, 60))
    }

    /** 长图：短边不小于下限（界面居中裁剪） */
    @Test
    fun displaySize_longImage_shortSideClamped() {
        assertThat(ImageSizing.displaySize(100, 3000, 160, 60)).isEqualTo(Dimension(60, 160))
    }

    @Test
    fun displaySize_unknown_square() {
        assertThat(ImageSizing.displaySize(0, 0, 150, 60)).isEqualTo(Dimension(100, 100))
    }
}
