package cn.android666.im.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.min

/**
 * 头像图片处理（开发计划 3.21）：按 EXIF 方向旋转、居中裁成正方形、缩放到 [SIZE] 像素、压缩为 JPEG。
 *
 * 不提供手动裁剪界面：头像显示为圆形，居中裁剪对大多数照片已经合适（决策记录 A70）。
 */
object AvatarImage {

    /** 头像边长（像素）：列表中 40dp、资料卡 64dp，高分屏下 256 像素足够清晰 */
    const val SIZE = 256

    /** JPEG 质量 */
    const val QUALITY = 85

    /** 头像的 MIME 类型 */
    const val MIME_TYPE = "image/jpeg"

    /**
     * 把任意图片转为头像 JPEG。
     *
     * @param input 原图数据（JPEG、PNG、WebP 等系统能解码的格式）
     * @return 头像 JPEG 数据
     * @throws IOException 无法解码
     */
    fun toAvatarJpeg(input: ByteArray): ByteArray {
        // 先只读尺寸，按 2 的幂缩小解码，避免大图占用过多内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法识别的图片")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(min(bounds.outWidth, bounds.outHeight), SIZE)
        }
        val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, options) ?: throw IOException("图片解码失败")
        val rotated = rotate(decoded, exifRotation(input))
        val square = centerCropSquare(rotated, SIZE)
        return ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
    }

    /**
     * 解码时的缩小倍数：保证短边不小于目标尺寸的最大 2 的幂。
     *
     * @param shortSide 原图短边
     * @param target 目标边长
     * @return inSampleSize
     */
    internal fun sampleSize(shortSide: Int, target: Int): Int {
        var size = 1
        while (shortSide / (size * 2) >= target) size *= 2
        return size
    }

    /**
     * 读取 EXIF 中的旋转角度（相机拍的照片像素方向与显示方向可能不同）。
     *
     * @param input 原图数据
     * @return 旋转角度：0、90、180、270
     */
    private fun exifRotation(input: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(input)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }

    /**
     * 旋转图片。
     *
     * @param bitmap 图片
     * @param degrees 角度
     * @return 旋转后的图片（角度为 0 时原样返回）
     */
    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 居中裁成正方形并缩放到指定边长（原图比目标小时不放大）。
     *
     * @param bitmap 图片
     * @param size 目标边长
     * @return 正方形图片
     */
    internal fun centerCropSquare(bitmap: Bitmap, size: Int): Bitmap {
        val side = min(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        val square = Bitmap.createBitmap(bitmap, x, y, side, side)
        val target = min(side, size)
        return if (target == side) square else square.scale(target, target)
    }
}
