package cn.android666.im.testing

import cn.android666.im.core.common.Clock

/**
 * 可控的假时钟，测试中通过 [advanceBy] 推进时间。
 *
 * @param startMillis 初始墙上时间，默认 2026-10-13T02:30:00.000Z（与协议规范示例一致）
 */
class FakeClock(startMillis: Long = 1_760_322_600_000L) : Clock {

    /** 当前墙上时间（毫秒），可直接赋值模拟用户修改系统时间 */
    var now: Long = startMillis

    /** 当前单调时间（毫秒） */
    var elapsed: Long = 0

    override fun nowMillis(): Long = now

    override fun elapsedMillis(): Long = elapsed

    /**
     * 同时推进墙上时间和单调时间。
     *
     * @param millis 推进的毫秒数
     */
    fun advanceBy(millis: Long) {
        now += millis
        elapsed += millis
    }
}
