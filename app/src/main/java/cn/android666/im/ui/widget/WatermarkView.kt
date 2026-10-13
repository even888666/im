package cn.android666.im.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.withRotation
import com.google.android.material.color.MaterialColors

/**
 * 水印（开发计划 4.18，功能清单 §7"聊天水印"）：在整个区域内倾斜平铺"姓名 工号"，颜色很淡，不拦截触摸。
 * 覆盖在聊天列表上方，截图外泄时能看出是谁截的。
 *
 * @constructor 由布局创建
 */
class WatermarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 水印文字；为 null 或空时不绘制 */
    var text: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 画笔：正文颜色、低不透明度 */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, resources.displayMetrics)
        color = MaterialColors.getColor(this@WatermarkView, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
        alpha = ALPHA
    }

    /** 行间距、列间距（像素） */
    private val gap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, GAP_DP, resources.displayMetrics)

    init {
        // 只用于显示：不响应触摸、无障碍服务不朗读
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * 绘制：以中心为原点旋转画布，在覆盖旋转后可见区域的网格上逐个画文字（奇数行错开半个单元）。
     *
     * @param canvas 画布
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val label = text?.takeIf { it.isNotBlank() } ?: return
        val cellWidth = paint.measureText(label) + gap
        val cellHeight = paint.textSize + gap
        // 旋转后要覆盖整个视图，网格范围取对角线长度
        val diagonal = Math.hypot(width.toDouble(), height.toDouble()).toFloat()
        canvas.withRotation(ANGLE, width / 2f, height / 2f) {
            var row = 0
            var y = (height - diagonal) / 2
            while (y < (height + diagonal) / 2) {
                var x = (width - diagonal) / 2 - if (row % 2 == 1) cellWidth / 2 else 0f
                while (x < (width + diagonal) / 2) {
                    drawText(label, x, y, paint)
                    x += cellWidth
                }
                y += cellHeight
                row++
            }
        }
    }

    companion object {
        /** 文字大小（sp） */
        private const val TEXT_SIZE_SP = 14f

        /** 文字之间的间距（dp） */
        private const val GAP_DP = 56f

        /** 倾斜角度 */
        private const val ANGLE = -20f

        /** 不透明度（0 ~ 255）：很淡，不影响阅读 */
        const val ALPHA = 24

        /**
         * 水印文字："姓名 工号"；组织架构中没有姓名时只显示工号。
         *
         * @param name 姓名
         * @param empNo 工号
         * @return 文字
         */
        fun label(name: String?, empNo: String): String = if (name.isNullOrBlank()) empNo else "$name $empNo"
    }
}
