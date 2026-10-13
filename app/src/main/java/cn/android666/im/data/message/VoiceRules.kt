package cn.android666.im.data.message

import kotlin.math.roundToInt

/**
 * 语音消息的规则（开发计划 5.5，协议规范 §7.3，纯逻辑）。
 */
object VoiceRules {

    /** 最短时长：不足 1 秒不发送 */
    const val MIN_MILLIS = 1_000L

    /** 最长时长：到 60 秒自动结束并发送 */
    const val MAX_MILLIS = 60_000L

    /** 剩余多少时间开始倒计时提示（"还可以说 N 秒"） */
    const val COUNTDOWN_MILLIS = 10_000L

    /**
     * 显示的秒数：四舍五入，至少 1 秒，最多 60 秒。
     *
     * @param millis 时长（毫秒）
     * @return 秒
     */
    fun seconds(millis: Long): Int = ((millis + 500) / 1000).toInt().coerceIn(1, (MAX_MILLIS / 1000).toInt())

    /**
     * 录音中的倒计时秒数。
     *
     * @param elapsedMillis 已录时长
     * @return 剩余秒数（向上取整）；还没到倒计时阶段时为 null
     */
    fun countdown(elapsedMillis: Long): Int? {
        val remaining = MAX_MILLIS - elapsedMillis
        if (remaining > COUNTDOWN_MILLIS) return null
        return ((remaining.coerceAtLeast(0) + 999) / 1000).toInt()
    }

    /**
     * 语音气泡的宽度：在最小与最大宽度之间，前 10 秒增长快、之后增长慢（与常见 IM 一致，短语音也容易点中）。
     *
     * @param millis 时长
     * @param minWidth 最小宽度（1 秒）
     * @param maxWidth 最大宽度（60 秒）
     * @return 宽度
     */
    fun bubbleWidth(millis: Long, minWidth: Int, maxWidth: Int): Int {
        val seconds = seconds(millis)
        val range = maxWidth - minWidth
        // 前 10 秒占 60% 的增长空间，其余 50 秒占 40%
        val fraction = if (seconds <= FAST_SECONDS) {
            (seconds - 1) / (FAST_SECONDS - 1).toDouble() * FAST_SHARE
        } else {
            FAST_SHARE + (seconds - FAST_SECONDS) / (60.0 - FAST_SECONDS) * (1 - FAST_SHARE)
        }
        return minWidth + (range * fraction).roundToInt()
    }

    /** 快速增长阶段的秒数 */
    private const val FAST_SECONDS = 10

    /** 快速增长阶段占的比例 */
    private const val FAST_SHARE = 0.6
}
