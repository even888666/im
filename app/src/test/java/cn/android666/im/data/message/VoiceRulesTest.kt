package cn.android666.im.data.message

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [VoiceRules] 测试：秒数、倒计时、气泡宽度 */
class VoiceRulesTest {

    @Test
    fun seconds_roundedAndClamped() {
        assertThat(VoiceRules.seconds(1_000)).isEqualTo(1)
        assertThat(VoiceRules.seconds(1_499)).isEqualTo(1)
        assertThat(VoiceRules.seconds(1_500)).isEqualTo(2)
        assertThat(VoiceRules.seconds(300)).isEqualTo(1)
        assertThat(VoiceRules.seconds(60_000)).isEqualTo(60)
        assertThat(VoiceRules.seconds(75_000)).isEqualTo(60)
    }

    @Test
    fun countdown_lastTenSeconds() {
        assertThat(VoiceRules.countdown(49_999)).isNull()
        assertThat(VoiceRules.countdown(50_000)).isEqualTo(10)
        assertThat(VoiceRules.countdown(50_001)).isEqualTo(10)
        assertThat(VoiceRules.countdown(59_000)).isEqualTo(1)
        assertThat(VoiceRules.countdown(59_999)).isEqualTo(1)
        assertThat(VoiceRules.countdown(60_000)).isEqualTo(0)
    }

    @Test
    fun bubbleWidth_growsFastThenSlow() {
        assertThat(VoiceRules.bubbleWidth(1_000, 100, 200)).isEqualTo(100)
        assertThat(VoiceRules.bubbleWidth(10_000, 100, 200)).isEqualTo(160)
        assertThat(VoiceRules.bubbleWidth(60_000, 100, 200)).isEqualTo(200)
        val w5 = VoiceRules.bubbleWidth(5_000, 100, 200)
        val w30 = VoiceRules.bubbleWidth(30_000, 100, 200)
        assertThat(w5).isIn(com.google.common.collect.Range.open(100, 160))
        assertThat(w30).isIn(com.google.common.collect.Range.open(160, 200))
    }
}
