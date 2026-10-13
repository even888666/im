package cn.android666.im.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import cn.android666.im.core.log.Logs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 处理后的一张图片。
 *
 * @property file 本地文件
 * @property width 宽（显示方向）
 * @property height 高（显示方向）
 * @property mime MIME 类型
 */
data class ProcessedImage(val file: File, val width: Int, val height: Int, val mime: String)

/**
 * 准备发送的图片：主图 + 缩略图。
 *
 * @property main 主图（压缩后的图片或原图）
 * @property thumbnail 缩略图
 * @property original 是否原图（未压缩）
 */
data class PreparedImage(val main: ProcessedImage, val thumbnail: ProcessedImage, val original: Boolean)

/**
 * 发送图片前的处理（开发计划 5.4，协议规范 §7.2）。接口便于在 JVM 测试中替换。
 */
interface ImageProcessor {

    /**
     * 生成主图和缩略图。
     *
     * @param source 用户选择的图片（已复制到本地）
     * @param outDir 输出目录
     * @param original 是否发送原图
     * @return 处理结果（文件都在 [outDir] 中，调用方负责清理）
     * @throws IOException 不是图片或无法解码
     */
    fun prepare(source: File, outDir: File, original: Boolean): PreparedImage
}

/**
 * 图片压缩（协议规范 §7.2）：
 * - 非原图：按 EXIF 方向旋转，长边 ≤ [MAX_LONG_SIDE]，JPEG 质量 [QUALITY]；GIF 不压缩（保留动画）；
 * - 原图：原样复制，不压缩；
 * - 缩略图：长边 ≤ [THUMB_LONG_SIDE]，JPEG，≤ [THUMB_MAX_BYTES] 字节（逐步降低质量，仍超出时再缩小）。
 *
 * 透明背景的图片（PNG、WebP）转 JPEG 前铺白底，避免透明区域变黑。
 */
class ImageCompressor : ImageProcessor {

    private val log = Logs.file(TAG)

    /**
     * 生成主图和缩略图。
     *
     * @param source 用户选择的图片
     * @param outDir 输出目录
     * @param original 是否发送原图
     * @return 处理结果
     * @throws IOException 不是图片或无法解码
     */
    override fun prepare(source: File, outDir: File, original: Boolean): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val mime = bounds.outMimeType
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || mime == null) throw IOException("无法识别的图片")
        val rotation = exifRotation(source)
        val shown = ImageSizing.rotated(bounds.outWidth, bounds.outHeight, rotation)
        outDir.mkdirs()
        val base = UUID.randomUUID().toString()
        val keepAsIs = original || mime == MIME_GIF
        val main = if (keepAsIs) {
            val target = File(outDir, "$base.${extensionOf(mime)}")
            source.copyTo(target, overwrite = true)
            ProcessedImage(target, shown.width, shown.height, mime)
        } else {
            val bitmap = decode(source, bounds.outWidth, bounds.outHeight, rotation, MAX_LONG_SIDE)
            val target = File(outDir, "$base.jpg")
            target.writeBytes(encodeJpeg(bitmap, QUALITY))
            ProcessedImage(target, bitmap.width, bitmap.height, MIME_JPEG)
        }
        val thumbBitmap = decode(source, bounds.outWidth, bounds.outHeight, rotation, THUMB_LONG_SIDE)
        val thumb = thumbnail(thumbBitmap)
        val thumbFile = File(outDir, "${base}_thumb.jpg")
        thumbFile.writeBytes(thumb.second)
        log.info(
            "图片处理完成：原 {}×{} {} {} 字节 → 主图 {}×{} {} 字节（原图={}），缩略图 {}×{} {} 字节",
            shown.width, shown.height, mime, source.length(), main.width, main.height, main.file.length(), keepAsIs,
            thumb.first.width, thumb.first.height, thumb.second.size,
        )
        return PreparedImage(
            main = main,
            thumbnail = ProcessedImage(thumbFile, thumb.first.width, thumb.first.height, MIME_JPEG),
            original = original,
        )
    }

    /**
     * 解码、旋转并缩放到长边不超过上限。
     *
     * @param source 图片文件
     * @param width 像素宽
     * @param height 像素高
     * @param rotation EXIF 旋转角度
     * @param maxLongSide 长边上限
     * @return 图片（显示方向）
     * @throws IOException 解码失败
     */
    private fun decode(source: File, width: Int, height: Int, rotation: Int, maxLongSide: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(width, height, maxLongSide) }
        val decoded = BitmapFactory.decodeFile(source.path, options) ?: throw IOException("图片解码失败")
        val target = ImageSizing.fit(decoded.width, decoded.height, maxLongSide)
        val scaled = if (target.width == decoded.width && target.height == decoded.height) decoded else decoded.scale(target.width, target.height)
        if (rotation == 0) return scaled
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
    }

    /**
     * 生成不超过 [THUMB_MAX_BYTES] 的缩略图：先逐步降低质量，到下限仍超出时缩小 [THUMB_SHRINK] 倍重试。
     *
     * @param bitmap 长边已不超过 [THUMB_LONG_SIDE] 的图片
     * @return 最终尺寸与 JPEG 数据
     */
    internal fun thumbnail(bitmap: Bitmap): Pair<Dimension, ByteArray> {
        var current = bitmap
        while (true) {
            var quality = QUALITY
            while (true) {
                val data = encodeJpeg(current, quality)
                if (data.size <= THUMB_MAX_BYTES) return Dimension(current.width, current.height) to data
                if (quality <= THUMB_MIN_QUALITY) break
                quality -= THUMB_QUALITY_STEP
            }
            val next = ImageSizing.fit(current.width, current.height, (current.longSideOf() * THUMB_SHRINK).toInt().coerceAtLeast(1))
            // 已经缩到 1 像素仍超出（不可能出现）时直接返回，避免死循环
            if (next.width == current.width && next.height == current.height) {
                return Dimension(current.width, current.height) to encodeJpeg(current, THUMB_MIN_QUALITY)
            }
            current = current.scale(next.width, next.height)
        }
    }

    /**
     * 编码为 JPEG；有透明通道时先铺白底。
     *
     * @param bitmap 图片
     * @param quality 质量 0 ~ 100
     * @return JPEG 数据
     */
    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val opaque = if (bitmap.hasAlpha()) {
            createBitmap(bitmap.width, bitmap.height).also {
                val canvas = Canvas(it)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { out ->
            opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    /**
     * 读取 EXIF 中的旋转角度（相机拍的照片像素方向与显示方向可能不同）。
     *
     * @param file 图片文件
     * @return 旋转角度：0、90、180、270
     */
    private fun exifRotation(file: File): Int = try {
        when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }

    /**
     * 长边。
     *
     * @return 像素
     */
    private fun Bitmap.longSideOf(): Int = maxOf(width, height)

    companion object {
        private const val TAG = "ImageCompressor"

        /** 非原图的长边上限 */
        const val MAX_LONG_SIDE = 1920

        /** 压缩质量 */
        const val QUALITY = 80

        /** 缩略图长边上限 */
        const val THUMB_LONG_SIDE = 320

        /** 缩略图大小上限 */
        const val THUMB_MAX_BYTES = 30 * 1024

        /** 缩略图的最低质量 */
        private const val THUMB_MIN_QUALITY = 40

        /** 缩略图每次降低的质量 */
        private const val THUMB_QUALITY_STEP = 10

        /** 缩略图降到最低质量仍超出时的缩小比例 */
        private const val THUMB_SHRINK = 0.75

        /** JPEG */
        const val MIME_JPEG = "image/jpeg"

        /** GIF（不压缩） */
        const val MIME_GIF = "image/gif"

        /**
         * 图片类型对应的扩展名。
         *
         * @param mime MIME 类型
         * @return 扩展名（不含点）
         */
        fun extensionOf(mime: String): String = when (mime) {
            MIME_JPEG -> "jpg"
            MIME_GIF -> "gif"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heif", "image/heic" -> "heic"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            else -> mime.substringAfter('/', "img").takeWhile { it.isLetterOrDigit() }.ifEmpty { "img" }
        }
    }
}
