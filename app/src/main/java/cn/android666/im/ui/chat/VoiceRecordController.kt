package cn.android666.im.ui.chat

import cn.android666.im.core.common.Clock
import cn.android666.im.core.log.Logs
import cn.android666.im.core.media.VoiceRecorder
import cn.android666.im.data.message.VoiceRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * 录音中的界面状态。
 *
 * @property elapsedMillis 已录时长
 * @property cancelZone 手指是否已上滑到取消区域（松开即取消）
 */
data class RecordState(val elapsedMillis: Long, val cancelZone: Boolean) {

    /** 倒计时秒数（最后 10 秒显示"还可以说 N 秒"）；不在倒计时阶段时为 null */
    val countdown: Int? get() = VoiceRules.countdown(elapsedMillis)
}

/** 一次录音的结果 */
sealed interface RecordResult {

    /**
     * 录音完成，可以发送。
     *
     * @property file 录音文件
     * @property durationMillis 时长
     */
    data class Finished(val file: File, val durationMillis: Long) : RecordResult

    /** 不足 1 秒（提示"说话时间太短"） */
    data object TooShort : RecordResult

    /** 用户上滑取消 */
    data object Canceled : RecordResult

    /** 无法录音（没有权限、麦克风被占用） */
    data object Failed : RecordResult
}

/**
 * "按住说话"的录音流程（开发计划 5.5，与界面无关的状态机）：
 * 按下开始录音 → 上滑进入取消区域（松开取消）/ 滑回 → 松开结束：不足 1 秒不发送，否则发送；录满 60 秒自动结束并发送。
 *
 * 时长按单调时间计算（录音器启动的延迟只有几十毫秒，足够准确）。
 *
 * @property recorder 录音器
 * @property clock 时间来源
 * @property scope 协程作用域（计时）
 * @property newFile 生成录音文件的位置
 * @property onResult 录音结束的结果（在 [scope] 中调用）
 */
class VoiceRecordController(
    private val recorder: VoiceRecorder,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val newFile: () -> File,
    private val onResult: suspend (RecordResult) -> Unit,
) {

    private val log = Logs.file(TAG)

    private val stateFlow = MutableStateFlow<RecordState?>(null)

    /** 录音中的状态；没在录音时为 null */
    val state: StateFlow<RecordState?> = stateFlow.asStateFlow()

    /** 开始录音的时间（单调时间） */
    private var startedAt = 0L

    /** 正在写入的文件 */
    private var file: File? = null

    /** 计时任务 */
    private var ticker: Job? = null

    /** 按下"按住说话"：开始录音 */
    fun press() {
        if (stateFlow.value != null) return
        val target = newFile()
        try {
            recorder.start(target)
        } catch (e: IOException) {
            log.warn("无法开始录音：{}", e.toString())
            scope.launch { onResult(RecordResult.Failed) }
            return
        }
        file = target
        startedAt = clock.elapsedMillis()
        stateFlow.value = RecordState(0, cancelZone = false)
        ticker = scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                val elapsed = clock.elapsedMillis() - startedAt
                if (elapsed >= VoiceRules.MAX_MILLIS) {
                    log.info("录满 60 秒，自动发送")
                    finish(cancel = false)
                    break
                }
                stateFlow.value = stateFlow.value?.copy(elapsedMillis = elapsed) ?: break
            }
        }
    }

    /**
     * 手指移动：是否在取消区域。
     *
     * @param cancelZone 是否已上滑到取消区域
     */
    fun move(cancelZone: Boolean) {
        val current = stateFlow.value ?: return
        if (current.cancelZone != cancelZone) stateFlow.value = current.copy(cancelZone = cancelZone)
    }

    /** 松开手指：在取消区域时取消，否则结束并发送 */
    fun release() {
        val current = stateFlow.value ?: return
        finish(cancel = current.cancelZone)
    }

    /** 异常中断（触摸被系统取消、离开页面）：取消录音 */
    fun abort() {
        if (stateFlow.value == null) return
        finish(cancel = true)
    }

    /**
     * 结束录音。
     *
     * @param cancel 是否取消
     */
    private fun finish(cancel: Boolean) {
        val target = file ?: return
        file = null
        ticker?.cancel()
        ticker = null
        stateFlow.value = null
        val duration = (clock.elapsedMillis() - startedAt).coerceAtMost(VoiceRules.MAX_MILLIS)
        val result = when {
            cancel -> {
                recorder.cancel()
                RecordResult.Canceled
            }
            duration < VoiceRules.MIN_MILLIS -> {
                recorder.cancel()
                RecordResult.TooShort
            }
            recorder.stop() -> RecordResult.Finished(target, duration)
            else -> RecordResult.TooShort
        }
        log.info("录音结束：{} 毫秒，结果={}", duration, result::class.simpleName)
        scope.launch { onResult(result) }
    }

    private companion object {
        const val TAG = "VoiceRecord"

        /** 计时刷新间隔 */
        const val TICK_MILLIS = 100L
    }
}
