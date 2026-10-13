package cn.android666.im.core.common

import cn.android666.im.testing.FakeClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ServerClock] 时钟偏差计算测试 */
class ServerClockTest {

    private val clock = FakeClock(startMillis = 1_000_000)
    private val serverClock = ServerClock(clock)

    @Test
    fun notCalibrated_usesLocalTime() {
        assertThat(serverClock.isCalibrated).isFalse()
        assertThat(serverClock.nowMillis()).isEqualTo(1_000_000)
    }

    /** 服务器比本地快 5 秒，往返 200ms：偏差 = 服务器时间 − (发出时间 + 100ms) */
    @Test
    fun calibrate_serverAhead_offsetAccountsForHalfRoundTrip() {
        val offset = serverClock.calibrate(serverMillis = 1_005_100, requestSentAtMillis = 1_000_000, roundTripMillis = 200)

        assertThat(offset).isEqualTo(5_000)
        assertThat(serverClock.isCalibrated).isTrue()
        clock.advanceBy(1_000)
        assertThat(serverClock.nowMillis()).isEqualTo(1_006_000)
    }

    @Test
    fun calibrate_serverBehind_negativeOffset() {
        serverClock.calibrate(serverMillis = 990_000, requestSentAtMillis = 1_000_000, roundTripMillis = 0)

        assertThat(serverClock.offsetMillis).isEqualTo(-10_000)
        assertThat(serverClock.toServerTime(2_000_000)).isEqualTo(1_990_000)
    }

    /** 往返耗时异常（负数）时按 0 处理，不会算出离谱的偏差 */
    @Test
    fun calibrate_negativeRoundTrip_treatedAsZero() {
        serverClock.calibrate(serverMillis = 1_000_000, requestSentAtMillis = 1_000_000, roundTripMillis = -50)

        assertThat(serverClock.offsetMillis).isEqualTo(0)
    }
}
