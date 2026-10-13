package cn.android666.im.ui.widget

import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/**
 * 文字头像：没有头像图片时，用姓名中的字作头像（开发计划 3.21 完成前所有头像都用它，之后作为默认头像）。
 *
 * - 中文姓名取最后两个字（三个字的姓名显示名字，如"张小明"显示"小明"）；两个字及以下全部显示；
 * - 英文姓名取前两个单词的首字母大写（如 John Smith → JS）；
 * - 背景色按 JID 从调色板中固定选取，同一个人的颜色总是相同。
 */
object NameAvatar {

    /** 背景调色板（Material 色板中饱和度适中、白字可读的颜色） */
    private val PALETTE = intArrayOf(
        0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF8E24AA.toInt(),
        0xFF00897B.toInt(), 0xFF3949AB.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt(),
    )

    /**
     * 头像上显示的文字。
     *
     * @param name 姓名
     * @return 一到两个字符；姓名为空时为空字符串
     */
    fun text(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        val ascii = trimmed.all { it.code < 128 }
        return if (ascii) {
            trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
        } else {
            // 去掉间隔号、空格等，只取文字
            val letters = trimmed.filter { it.isLetterOrDigit() }
            if (letters.length <= 2) letters else letters.takeLast(2)
        }
    }

    /**
     * 背景色在调色板中的下标。
     *
     * @param key 用于选色的键（一般为 JID）
     * @return 下标
     */
    fun colorIndex(key: String): Int = Math.floorMod(key.hashCode(), PALETTE.size)

    /**
     * 把文字头像显示到 TextView 上（圆形背景 + 白字）。
     *
     * @param view 头像 TextView
     * @param name 姓名
     * @param key 选色的键（一般为 JID）
     */
    fun bind(view: TextView, name: String, key: String) {
        view.text = text(name)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(PALETTE[colorIndex(key)])
        }
    }
}
