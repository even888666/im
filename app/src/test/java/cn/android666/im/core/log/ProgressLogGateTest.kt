package cn.android666.im.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ProgressLogGate] 进度节点测试 */
class ProgressLogGateTest {

    /** 进度逐个递增时，只在 0/25/50/75/100 打印 */
    @Test
    fun shouldLog_incrementalProgress_onlyAtQuarterNodes() {
        val gate = ProgressLogGate()
        val logged = (0..100).filter { gate.shouldLog(it) }

        assertThat(logged).containsExactly(0, 25, 50, 75, 100).inOrder()
    }

    /** 进度跳跃时只打印一次 */
    @Test
    fun shouldLog_jump_logsOnce() {
        val gate = ProgressLogGate()
        assertThat(gate.shouldLog(10)).isTrue()   // 进入 0 节点
        assertThat(gate.shouldLog(60)).isTrue()   // 直接跳到 50 节点
        assertThat(gate.shouldLog(70)).isFalse()
        assertThat(gate.shouldLog(75)).isTrue()
    }

    /** 进度回退（如重试）不会重复打印已打印过的节点 */
    @Test
    fun shouldLog_goingBack_doesNotRepeat() {
        val gate = ProgressLogGate()
        gate.shouldLog(50)
        assertThat(gate.shouldLog(30)).isFalse()
    }

    @Test
    fun shouldLog_outOfRange_clamped() {
        val gate = ProgressLogGate()
        assertThat(gate.shouldLog(-5)).isTrue()
        assertThat(gate.shouldLog(150)).isTrue()
        assertThat(gate.shouldLog(100)).isFalse()
    }

    @Test
    fun shouldLog_bytes_convertedToPercent() {
        val gate = ProgressLogGate()
        assertThat(gate.shouldLog(0L, 1_000L)).isTrue()
        assertThat(gate.shouldLog(240L, 1_000L)).isFalse()
        assertThat(gate.shouldLog(250L, 1_000L)).isTrue()
        assertThat(gate.shouldLog(1_000L, 1_000L)).isTrue()
    }

    /** 总大小未知时只在开始打印一次 */
    @Test
    fun shouldLog_unknownTotal_onlyOnce() {
        val gate = ProgressLogGate()
        assertThat(gate.shouldLog(100L, -1L)).isTrue()
        assertThat(gate.shouldLog(500L, -1L)).isFalse()
    }

    @Test
    fun customStep() {
        val gate = ProgressLogGate(stepPercent = 50)
        assertThat((0..100).filter { gate.shouldLog(it) }).containsExactly(0, 50, 100).inOrder()
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidStep_throws() {
        ProgressLogGate(stepPercent = 0)
    }
}
