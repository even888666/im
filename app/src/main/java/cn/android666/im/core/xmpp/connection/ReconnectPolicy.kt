package cn.android666.im.core.xmpp.connection

import kotlin.random.Random

/**
 * 断线重连的退避策略（协议规范 §2.3、Android工程架构.md §4.3）。纯 Kotlin，便于单元测试。
 *
 * 断线后立即尝试一次，之后依次等待 2、4、8、16、32 秒，之后每次 60 秒（上限）。
 * 网络恢复、回到前台等时机调用 [reset]，下一次立即重连。
 *
 * 除第一次外，每次等待时间加入 ±[jitterRatio] 的随机抖动：服务器重启时上万台终端同时断线，
 * 如果都在同一时刻重连，会瞬间压垮服务器（"惊群"），抖动把重连分散开。
 *
 * 非线程安全，由 XmppConnectionManager 在单个协程中使用。
 *
 * @property delaysMillis 各次重连前的等待时间（毫秒），超过列表长度后一直使用最后一个值
 * @property jitterRatio 随机抖动比例，0 表示不抖动
 * @property random 随机数来源，测试时可传入固定种子
 */
class ReconnectPolicy(
    private val delaysMillis: List<Long> = DEFAULT_DELAYS_MILLIS,
    private val jitterRatio: Double = DEFAULT_JITTER_RATIO,
    private val random: Random = Random.Default,
) {

    init {
        require(delaysMillis.isNotEmpty()) { "delaysMillis 不能为空" }
        require(delaysMillis.all { it >= 0 }) { "等待时间不能为负数" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio 必须在 0..1 之间：$jitterRatio" }
    }

    /** 自上次 [reset] 以来已经安排的重连次数 */
    var attempts: Int = 0
        private set

    /**
     * 计算下一次重连前应等待的时间，并把重连次数加 1。
     *
     * @return 等待时间（毫秒），第一次为 0（立即重连）
     */
    fun nextDelayMillis(): Long {
        val base = delaysMillis[minOf(attempts, delaysMillis.lastIndex)]
        attempts++
        if (base == 0L || jitterRatio == 0.0) return base
        // 在 [base × (1 - jitter), base × (1 + jitter)] 内均匀取值
        val jitter = (random.nextDouble() * 2 - 1) * jitterRatio
        return (base * (1 + jitter)).toLong().coerceAtLeast(0)
    }

    /** 重置：连接成功、网络恢复或回到前台时调用，下一次立即重连 */
    fun reset() {
        attempts = 0
    }

    companion object {
        /** 立即、2、4、8、16、32、60 秒（协议规范 §2.3） */
        val DEFAULT_DELAYS_MILLIS: List<Long> = listOf(0, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000)

        /** 默认抖动 ±20% */
        const val DEFAULT_JITTER_RATIO = 0.2
    }
}
