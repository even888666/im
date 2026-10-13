package cn.android666.im.core.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 宽高（像素或 dp）。
 *
 * @property width 宽
 * @property height 高
 */
data class Dimension(val width: Int, val height: Int) {

    /** 长边 */
    val longSide: Int get() = max(width, height)
}

/**
 * 图片尺寸计算（开发计划 5.4，纯逻辑）：压缩目标尺寸、解码缩小倍数、聊天气泡中的显示尺寸。
 */
object ImageSizing {

    /**
     * 等比缩小到长边不超过上限（比上限小时不放大）。
     *
     * @param width 原宽
     * @param height 原高
     * @param maxLongSide 长边上限
     * @return 目标尺寸（每边至少 1）
     */
    fun fit(width: Int, height: Int, maxLongSide: Int): Dimension {
        require(width > 0 && height > 0) { "尺寸无效：$width × $height" }
        val long = max(width, height)
        if (long <= maxLongSide) return Dimension(width, height)
        val scale = maxLongSide.toDouble() / long
        return Dimension(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    /**
     * 解码时的缩小倍数（BitmapFactory.Options.inSampleSize）：解码后长边仍不小于目标的最大 2 的幂，
     * 避免大图整张解码占用过多内存，又保证之后精确缩放时不损失清晰度。
     *
     * @param width 原宽
     * @param height 原高
     * @param maxLongSide 目标长边
     * @return 2 的幂，至少为 1
     */
    fun sampleSize(width: Int, height: Int, maxLongSide: Int): Int {
        val long = max(width, height)
        var size = 1
        while (long / (size * 2) >= maxLongSide) size *= 2
        return size
    }

    /**
     * 按 EXIF 旋转后的尺寸：旋转 90° / 270° 时宽高互换。
     *
     * @param width 像素宽
     * @param height 像素高
     * @param rotation 旋转角度：0、90、180、270
     * @return 显示方向的尺寸
     */
    fun rotated(width: Int, height: Int, rotation: Int): Dimension =
        if (rotation == 90 || rotation == 270) Dimension(height, width) else Dimension(width, height)

    /**
     * 聊天气泡中图片的显示尺寸：长边为 [maxSide]（原图更小时按原图，但不小于 [minSide]）；
     * 极端长宽比（长图、全景图）时短边不小于 [minSide]，超出部分由界面居中裁剪。
     *
     * @param width 图片宽（缩略图或原图，未知时传 0）
     * @param height 图片高
     * @param maxSide 长边上限
     * @param minSide 短边下限
     * @return 显示尺寸；图片尺寸未知时为 [minSide] 到 [maxSide] 之间的正方形
     */
    fun displaySize(width: Int, height: Int, maxSide: Int, minSide: Int): Dimension {
        if (width <= 0 || height <= 0) return Dimension(maxSide * 2 / 3, maxSide * 2 / 3)
        val long = max(width, height).coerceIn(minSide, maxSide)
        val ratio = min(width, height).toDouble() / max(width, height)
        val short = (long * ratio).roundToInt().coerceIn(minSide, long)
        return if (width >= height) Dimension(long, short) else Dimension(short, long)
    }
}
