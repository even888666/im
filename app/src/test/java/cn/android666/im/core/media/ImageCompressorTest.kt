package cn.android666.im.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** [ImageCompressor] 测试（Robolectric 原生图形，真实编解码） */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCompressorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val compressor = ImageCompressor()

    /**
     * 生成图片文件。
     *
     * @param width 宽
     * @param height 高
     * @param format 格式
     * @param noise 是否随机像素（压缩率很低，用于测试缩略图大小上限）
     * @return 文件
     */
    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, noise: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (noise) {
            val random = Random(1)
            val pixels = IntArray(width * height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } else {
            bitmap.eraseColor(Color.BLUE)
        }
        val ext = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        return temp.newFile("src_${width}x$height.$ext").also { f -> f.outputStream().use { bitmap.compress(format, 95, it) } }
    }

    /**
     * 读取图片文件的尺寸与类型。
     *
     * @param file 文件
     * @return 宽、高、MIME
     */
    private fun info(file: File): Triple<Int, Int, String?> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return Triple(options.outWidth, options.outHeight, options.outMimeType)
    }

    @Test
    fun largeImage_compressedTo1920AndThumbnail320() {
        val result = compressor.prepare(image(3000, 2000), temp.newFolder("out"), original = false)
        assertThat(info(result.main.file)).isEqualTo(Triple(1920, 1280, "image/jpeg"))
        assertThat(result.main.width to result.main.height).isEqualTo(1920 to 1280)
        assertThat(result.main.mime).isEqualTo("image/jpeg")
        assertThat(info(result.thumbnail.file)).isEqualTo(Triple(320, 213, "image/jpeg"))
        assertThat(result.thumbnail.file.length()).isAtMost(ImageCompressor.THUMB_MAX_BYTES.toLong())
        assertThat(result.original).isFalse()
    }

    /** 原图：原样复制，宽高与类型取自原图 */
    @Test
    fun original_copiedAsIs() {
        val source = image(2500, 1000, Bitmap.CompressFormat.PNG)
        val result = compressor.prepare(source, temp.newFolder("out"), original = true)
        assertThat(result.main.file.readBytes()).isEqualTo(source.readBytes())
        assertThat(result.main.file.name).endsWith(".png")
        assertThat(result.main.mime).isEqualTo("image/png")
        assertThat(result.main.width to result.main.height).isEqualTo(2500 to 1000)
        assertThat(result.original).isTrue()
        assertThat(info(result.thumbnail.file)).isEqualTo(Triple(320, 128, "image/jpeg"))
    }

    /** 小图不放大；PNG 转为 JPEG */
    @Test
    fun smallPng_convertedToJpegWithoutUpscale() {
        val result = compressor.prepare(image(200, 100, Bitmap.CompressFormat.PNG), temp.newFolder("out"), original = false)
        assertThat(info(result.main.file)).isEqualTo(Triple(200, 100, "image/jpeg"))
        assertThat(info(result.thumbnail.file)).isEqualTo(Triple(200, 100, "image/jpeg"))
    }

    /** 难以压缩的图片：缩略图仍不超过 30 KB */
    @Test
    fun noisyImage_thumbnailWithinLimit() {
        val result = compressor.prepare(image(1600, 1600, noise = true), temp.newFolder("out"), original = false)
        assertThat(result.thumbnail.file.length()).isAtMost(ImageCompressor.THUMB_MAX_BYTES.toLong())
        val (w, h, _) = info(result.thumbnail.file)
        assertThat(w).isEqualTo(result.thumbnail.width)
        assertThat(h).isEqualTo(result.thumbnail.height)
        assertThat(maxOf(w, h)).isAtMost(320)
    }

    /** 相机照片带 EXIF 旋转：输出按显示方向 */
    @Test
    fun exifRotation_applied() {
        val source = image(400, 300)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val result = compressor.prepare(source, temp.newFolder("out"), original = false)
        assertThat(info(result.main.file)).isEqualTo(Triple(300, 400, "image/jpeg"))
        assertThat(info(result.thumbnail.file)).isEqualTo(Triple(240, 320, "image/jpeg"))
    }

    /** 原图带 EXIF 旋转：文件不变，宽高按显示方向 */
    @Test
    fun original_exifRotation_reportsShownSize() {
        val source = image(400, 300)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
            saveAttributes()
        }
        val result = compressor.prepare(source, temp.newFolder("out"), original = true)
        assertThat(result.main.width to result.main.height).isEqualTo(300 to 400)
    }

    @Test
    fun notAnImage_throws() {
        val source = temp.newFile("a.txt").apply { writeText("hello") }
        assertThrows(IOException::class.java) { compressor.prepare(source, temp.newFolder("out"), original = false) }
    }

    @Test
    fun extensionOf_knownTypes() {
        assertThat(ImageCompressor.extensionOf("image/jpeg")).isEqualTo("jpg")
        assertThat(ImageCompressor.extensionOf("image/png")).isEqualTo("png")
        assertThat(ImageCompressor.extensionOf("image/gif")).isEqualTo("gif")
        assertThat(ImageCompressor.extensionOf("image/x-foo")).isEqualTo("x")
    }
}
