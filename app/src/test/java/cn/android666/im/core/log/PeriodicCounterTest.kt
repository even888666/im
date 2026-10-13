package cn.android666.im.core.log

import cn.android666.im.testing.FakeClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [PeriodicCounter] 周期汇总测试 */
class PeriodicCounterTest {

    private val clock = FakeClock()
    private val outputs = mutableListOf<String>()
    private val counter = PeriodicCounter("presence", periodMillis = 60_000, clock = clock) { outputs += it }

    /** 周期内的事件只计数，不输出 */
    @Test
    fun record_withinPeriod_noOutput() {
        repeat(1_000) { counter.record("available") }
        clock.advanceBy(59_999)
        counter.record("available")

        assertThat(outputs).isEmpty()
    }

    /** 周期结束后的第一次 record 输出一条汇总，包含所有事件键 */
    @Test
    fun record_afterPeriod_outputsOneSummary() {
        repeat(3) { counter.record("available") }
        counter.record("unavailable")
        clock.advanceBy(60_000)
        counter.record("available")

        assertThat(outputs).containsExactly("presence 统计（60s）：available 4 次，unavailable 1 次")
    }

    /** 输出后开始新周期，计数清零 */
    @Test
    fun record_newPeriod_countsReset() {
        counter.record("a")
        clock.advanceBy(60_000)
        counter.record("a")
        counter.record("a")
        clock.advanceBy(60_000)
        counter.record("a")

        assertThat(outputs).containsExactly(
            "presence 统计（60s）：a 2 次",
            "presence 统计（60s）：a 2 次",
        ).inOrder()
    }

    @Test
    fun record_withDuration_outputsAverageAndMax() {
        counter.record("ping", 20)
        counter.record("ping", 40)
        counter.record("ping", 120)
        clock.advanceBy(60_000)
        counter.flush()

        assertThat(outputs).containsExactly("presence 统计（60s）：ping 3 次（平均 60ms，最大 120ms）")
    }

    @Test
    fun flush_outputsPartialPeriod() {
        counter.record("a")
        clock.advanceBy(5_000)
        counter.flush()

        assertThat(outputs).containsExactly("presence 统计（5s）：a 1 次")
    }

    @Test
    fun flush_noEvents_noOutput() {
        clock.advanceBy(120_000)
        counter.flush()

        assertThat(outputs).isEmpty()
    }

    /** 多线程同时计数，总数不丢失 */
    @Test
    fun record_concurrent_countsAreExact() {
        val threads = List(8) { Thread { repeat(10_000) { counter.record("a") } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        counter.flush()

        assertThat(outputs).containsExactly("presence 统计（0s）：a 80000 次")
    }
}
