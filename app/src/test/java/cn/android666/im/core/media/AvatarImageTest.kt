package cn.android666.im.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException

/** [AvatarImage] 测试：居中裁成正方形、缩放、JPEG（Robolectric 原生图形，真实编解码） */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarImageTest {

    /**
     * 生成 PNG 图片。
     *
     * @param width 宽
     * @param height 高
     * @return PNG 数据
     */
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    /**
     * 解码 JPEG 并返回尺寸。
     *
     * @param data 图片数据
     * @return 宽 × 高
     */
    private fun size(data: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        assertThat(options.outMimeType).isEqualTo("image/jpeg")
        return options.outWidth to options.outHeight
    }

    @Test
    fun largeImage_croppedAndScaledTo256() {
        assertThat(size(AvatarImage.toAvatarJpeg(png(1000, 800)))).isEqualTo(256 to 256)
    }

    /** 比目标小的图片不放大，只裁成正方形 */
    @Test
    fun smallImage_notUpscaled() {
        assertThat(size(AvatarImage.toAvatarJpeg(png(200, 120)))).isEqualTo(120 to 120)
    }

    @Test
    fun invalidData_throws() {
        assertThrows(IOException::class.java) { AvatarImage.toAvatarJpeg(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun sampleSize() {
        assertThat(AvatarImage.sampleSize(4000, 256)).isEqualTo(8)
        assertThat(AvatarImage.sampleSize(511, 256)).isEqualTo(1)
        assertThat(AvatarImage.sampleSize(512, 256)).isEqualTo(2)
    }
}
