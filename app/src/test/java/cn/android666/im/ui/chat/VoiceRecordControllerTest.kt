package cn.android666.im.ui.chat

import cn.android666.im.core.common.Clock
import cn.android666.im.data.message.VoiceRules
import cn.android666.im.testing.FakeVoiceRecorder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** [VoiceRecordController] 测试：按住说话的状态机，虚拟时间 */
class VoiceRecordControllerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val recorder = FakeVoiceRecorder()
    private val results = mutableListOf<RecordResult>()

    /** 被测对象（计时用虚拟时间） */
    private fun TestScope.controller(): VoiceRecordController {
        val clock = object : Clock {
            override fun nowMillis() = testScheduler.currentTime
            override fun elapsedMillis() = testScheduler.currentTime
        }
        var n = 0
        return VoiceRecordController(recorder, clock, backgroundScope, { temp.root.resolve("v${++n}.m4a") }) { results += it }
    }

    @Test
    fun pressAndRelease_finished() = runTest {
        val c = controller()
        c.press()
        assertThat(c.state.value).isEqualTo(RecordState(0, false))
        advanceTimeBy(3_050)
        runCurrent()
        assertThat(c.state.value!!.elapsedMillis).isEqualTo(3_000)

        c.release()
        runCurrent()
        assertThat(c.state.value).isNull()
        val finished = results.single() as RecordResult.Finished
        assertThat(finished.durationMillis).isEqualTo(3_050)
        assertThat(finished.file.length()).isGreaterThan(0)
        assertThat(recorder.calls).containsExactly("start", "stop").inOrder()
    }

    @Test
    fun underOneSecond_tooShort() = runTest {
        val c = controller()
        c.press()
        advanceTimeBy(999)
        c.release()
        runCurrent()
        assertThat(results).containsExactly(RecordResult.TooShort)
        assertThat(recorder.calls).containsExactly("start", "cancel").inOrder()
        assertThat(temp.root.listFiles()).isEmpty()
    }

    /** 上滑进入取消区域后松开：取消；滑回来再松开：发送 */
    @Test
    fun slideUp_cancel_slideBack_send() = runTest {
        val c = controller()
        c.press()
        advanceTimeBy(2_000)
        c.move(cancelZone = true)
        assertThat(c.state.value!!.cancelZone).isTrue()
        c.release()
        runCurrent()
        assertThat(results).containsExactly(RecordResult.Canceled)

        c.press()
        advanceTimeBy(2_000)
        c.move(cancelZone = true)
        c.move(cancelZone = false)
        c.release()
        runCurrent()
        assertThat(results.last()).isInstanceOf(RecordResult.Finished::class.java)
    }

    /** 录满 60 秒自动结束并发送；最后 10 秒倒计时 */
    @Test
    fun sixtySeconds_autoFinish() = runTest {
        val c = controller()
        c.press()
        advanceTimeBy(50_050)
        runCurrent()
        assertThat(c.state.value!!.countdown).isEqualTo(10)
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(c.state.value).isNull()
        assertThat((results.single() as RecordResult.Finished).durationMillis).isEqualTo(VoiceRules.MAX_MILLIS)
        // 自动结束后松开手指不再处理
        c.release()
        runCurrent()
        assertThat(results).hasSize(1)
    }

    @Test
    fun startFails_failed() = runTest {
        recorder.startError = IOException("没有权限")
        val c = controller()
        c.press()
        runCurrent()
        assertThat(c.state.value).isNull()
        assertThat(results).containsExactly(RecordResult.Failed)
    }

    @Test
    fun abort_canceled() = runTest {
        val c = controller()
        c.press()
        advanceTimeBy(5_000)
        c.abort()
        runCurrent()
        assertThat(results).containsExactly(RecordResult.Canceled)
        // 没在录音时 abort 什么也不做
        c.abort()
        runCurrent()
        assertThat(results).hasSize(1)
    }

    /** 录音器停止失败（没有采集到数据）：按太短处理 */
    @Test
    fun stopFails_tooShort() = runTest {
        recorder.stopResult = false
        val c = controller()
        c.press()
        advanceTimeBy(2_000)
        c.release()
        runCurrent()
        assertThat(results).containsExactly(RecordResult.TooShort)
    }
}
