package cn.android666.im.core.common

/**
 * 时间来源。依赖时间的逻辑（撤回时限、通话超时、日志统计周期等）都通过它取时间，
 * 测试时可替换为可控的假时钟（见 Android工程架构.md §9.2）。
 */
interface Clock {

    /**
     * 当前的本地墙上时间（可能被用户修改或被网络校时调整）。
     *
     * @return 自 1970-01-01T00:00:00Z 起的毫秒数
     */
    fun nowMillis(): Long

    /**
     * 单调递增的时间，不受修改系统时间影响，只适合计算时间间隔，不能当作日期。
     *
     * @return 某个固定起点起经过的毫秒数
     */
    fun elapsedMillis(): Long
}

/** 系统时钟。命名避开 `android.os.SystemClock`，防止误导入 */
object SystemTimeClock : Clock {

    /** @return [System.currentTimeMillis] */
    override fun nowMillis(): Long = System.currentTimeMillis()

    /** @return [System.nanoTime] 换算成毫秒 */
    override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000
}
