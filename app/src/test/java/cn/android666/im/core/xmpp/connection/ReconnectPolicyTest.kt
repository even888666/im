package cn.android666.im.core.xmpp.connection

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

/** [ReconnectPolicy] 退避序列测试 */
class ReconnectPolicyTest {

    /** 不抖动时严格按协议规范 §2.3：立即、2、4、8、16、32、60 秒，之后保持 60 秒 */
    @Test
    fun noJitter_followsProtocolSequence() {
        val policy = ReconnectPolicy(jitterRatio = 0.0)

        val delays = List(9) { policy.nextDelayMillis() }

        assertThat(delays).containsExactly(0L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L)
            .inOrder()
        assertThat(policy.attempts).isEqualTo(9)
    }

    @Test
    fun reset_nextIsImmediate() {
        val policy = ReconnectPolicy(jitterRatio = 0.0)
        repeat(5) { policy.nextDelayMillis() }

        policy.reset()

        assertThat(policy.attempts).isEqualTo(0)
        assertThat(policy.nextDelayMillis()).isEqualTo(0L)
        assertThat(policy.nextDelayMillis()).isEqualTo(2_000L)
    }

    /** 抖动后的等待时间在 ±20% 范围内，第一次仍然立即重连 */
    @Test
    fun jitter_withinRange_firstStillImmediate() {
        repeat(200) { seed ->
            val policy = ReconnectPolicy(random = Random(seed))
            assertThat(policy.nextDelayMillis()).isEqualTo(0L)
            assertThat(policy.nextDelayMillis()).isIn(1_600L..2_400L)
            assertThat(policy.nextDelayMillis()).isIn(3_200L..4_800L)
            repeat(10) { policy.nextDelayMillis() }
            assertThat(policy.nextDelayMillis()).isIn(48_000L..72_000L)
        }
    }

    /** 抖动确实把不同终端的重连时间分散开 */
    @Test
    fun jitter_spreadsClients() {
        val secondDelays = List(100) { seed ->
            ReconnectPolicy(random = Random(seed)).apply { nextDelayMillis() }.nextDelayMillis()
        }
        assertThat(secondDelays.toSet().size).isGreaterThan(50)
    }

    @Test
    fun customDelays() {
        val policy = ReconnectPolicy(delaysMillis = listOf(100, 200), jitterRatio = 0.0)
        assertThat(List(3) { policy.nextDelayMillis() }).containsExactly(100L, 200L, 200L).inOrder()
    }

    @Test
    fun invalidArguments_rejected() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(delaysMillis = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(delaysMillis = listOf(-1)) }
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(jitterRatio = 1.5) }
    }
}
