package cn.android666.im.core.log

import cn.android666.im.core.common.Clock

/**
 * 高频事件的周期汇总统计，用于避免刷屏日志（见 Android工程架构.md §8"禁止刷屏"）。
 *
 * 高频事件（presence、心跳、流管理确认等）不逐条打印，而是调用 [record] 计数；
 * 每过一个统计周期，在下一次 [record] 时输出**一条**汇总，例如：
 * `presence 统计（60s）：available 1234 次，unavailable 56 次`；
 * 带耗时的事件还会输出平均和最大耗时：`ping 12 次（平均 35ms，最大 120ms）`。
 *
 * 汇总由事件驱动（不开定时器）：周期结束后没有新事件时不会输出，需要时调用 [flush]
 * （例如断线、退出登录时把最后一个周期的统计打出来）。
 *
 * 线程安全：所有方法都加锁，可在 Smack 的多个回调线程中同时调用。
 *
 * @property name 统计名称，出现在汇总日志开头
 * @property periodMillis 统计周期（毫秒）
 * @property clock 时间来源，使用单调时间，不受修改系统时间影响
 * @property output 汇总输出，默认写入业务日志；测试时可替换
 */
class PeriodicCounter(
    private val name: String,
    private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
    private val clock: Clock,
    private val output: (String) -> Unit = { Logs.file(TAG).info(it) },
) {

    /** 单个事件键的统计数据 */
    private class Stat {
        /** 次数 */
        var count = 0L

        /** 带耗时的次数（只有这部分参与平均值计算） */
        var timedCount = 0L

        /** 耗时总和（毫秒） */
        var totalMillis = 0L

        /** 最大耗时（毫秒） */
        var maxMillis = 0L
    }

    /** 锁对象 */
    private val lock = Any()

    /** 本周期的统计，按事件键保持插入顺序，便于输出稳定 */
    private val stats = LinkedHashMap<String, Stat>()

    /** 本周期开始的单调时间 */
    private var periodStart = clock.elapsedMillis()

    /**
     * 记录一次事件。如果距本周期开始已超过 [periodMillis]，先输出本周期汇总再开始新周期。
     *
     * @param key 事件键，如 `available`、`ping`
     * @param durationMillis 本次事件的耗时（毫秒），不需要统计耗时时传 null
     */
    fun record(key: String, durationMillis: Long? = null) {
        val summary = synchronized(lock) {
            val stat = stats.getOrPut(key) { Stat() }
            stat.count++
            if (durationMillis != null) {
                stat.timedCount++
                stat.totalMillis += durationMillis
                stat.maxMillis = maxOf(stat.maxMillis, durationMillis)
            }
            if (clock.elapsedMillis() - periodStart >= periodMillis) takeSummaryLocked() else null
        }
        // 在锁外输出，避免写日志的耗时阻塞其他线程计数
        summary?.let(output)
    }

    /**
     * 立即输出当前周期的汇总（没有任何事件时不输出），并开始新周期。
     */
    fun flush() {
        val summary = synchronized(lock) { takeSummaryLocked() }
        summary?.let(output)
    }

    /**
     * 生成汇总文本并清空统计。调用方必须持有 [lock]。
     *
     * @return 汇总文本；本周期没有事件时返回 null
     */
    private fun takeSummaryLocked(): String? {
        val now = clock.elapsedMillis()
        val elapsedSeconds = (now - periodStart) / 1000
        periodStart = now
        if (stats.isEmpty()) return null
        val body = stats.entries.joinToString("，") { (key, s) ->
            if (s.timedCount > 0) {
                "$key ${s.count} 次（平均 ${s.totalMillis / s.timedCount}ms，最大 ${s.maxMillis}ms）"
            } else {
                "$key ${s.count} 次"
            }
        }
        stats.clear()
        return "$name 统计（${elapsedSeconds}s）：$body"
    }

    companion object {
        /** 默认统计周期：60 秒 */
        const val DEFAULT_PERIOD_MILLIS = 60_000L

        private const val TAG = "PeriodicCounter"
    }
}
