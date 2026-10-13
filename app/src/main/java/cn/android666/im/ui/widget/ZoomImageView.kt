package cn.android666.im.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 可缩放的图片（大图预览，开发计划 5.4）：初始等比完整显示并居中；双指缩放（1 ~ [MAX_SCALE] 倍）、双击放大 / 还原、
 * 放大后拖动；单击回调 [onTap]（预览页用来关闭）。
 *
 * @param context Context
 * @param attrs 布局属性
 */
class ZoomImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {

    /** 单击（没有缩放、拖动） */
    var onTap: (() -> Unit)? = null

    /** 当前变换 */
    private val current = Matrix()

    /** 完整显示时的缩放比例（相对图片原尺寸） */
    private var baseScale = 1f

    /** 临时矩形 */
    private val rect = RectF()

    /** 临时数组 */
    private val values = FloatArray(9)

    /** 双指缩放 */
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (zoom() * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
                val factor = target / zoom()
                current.postScale(factor, factor, detector.focusX, detector.focusY)
                applyClamped()
                return true
            }
        },
    )

    /** 单击、双击、拖动 */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom() > 1.01f) {
                    fit()
                } else {
                    current.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.x, e.y)
                    applyClamped()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (zoom() <= 1.01f) return false
                current.postTranslate(-distanceX, -distanceY)
                applyClamped()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    /**
     * 设置图片：重新完整显示。
     *
     * @param drawable 图片
     */
    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    /**
     * 尺寸变化：重新完整显示。
     *
     * @param w 新宽
     * @param h 新高
     * @param oldw 旧宽
     * @param oldh 旧高
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    /**
     * 触摸事件交给手势识别。
     *
     * @param event 事件
     * @return 是否处理
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // 放大后由本控件处理拖动，不让父布局拦截
        if (zoom() > 1.01f) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    /** 等比完整显示并居中 */
    private fun fit() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0 || width == 0 || height == 0) return
        baseScale = minOf(width / dw, height / dh)
        current.reset()
        current.postScale(baseScale, baseScale)
        current.postTranslate((width - dw * baseScale) / 2, (height - dh * baseScale) / 2)
        imageMatrix = current
    }

    /**
     * 相对完整显示的放大倍数。
     *
     * @return 倍数（1 为完整显示）
     */
    private fun zoom(): Float {
        current.getValues(values)
        return if (baseScale == 0f) 1f else values[Matrix.MSCALE_X] / baseScale
    }

    /** 限制拖动范围：图片比控件小的方向居中，比控件大的方向不露出空白 */
    private fun applyClamped() {
        val d = drawable ?: return
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        current.mapRect(rect)
        current.postTranslate(correction(rect.left, rect.right, width.toFloat()), correction(rect.top, rect.bottom, height.toFloat()))
        imageMatrix = current
    }

    companion object {
        /** 最大放大倍数 */
        const val MAX_SCALE = 4f

        /** 双击放大的倍数 */
        const val DOUBLE_TAP_SCALE = 2.5f

        /**
         * 一个方向上需要的平移修正。
         *
         * @param start 图片起点（左 / 上）
         * @param end 图片终点（右 / 下）
         * @param viewSize 控件尺寸
         * @return 修正量：图片比控件小时移到居中；比控件大时消除露出的空白
         */
        fun correction(start: Float, end: Float, viewSize: Float): Float {
            val size = end - start
            return when {
                size <= viewSize -> (viewSize - size) / 2 - start
                start > 0 -> -start
                end < viewSize -> viewSize - end
                else -> 0f
            }
        }
    }
}
