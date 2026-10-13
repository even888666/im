package cn.android666.im.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import cn.android666.im.core.log.Logs
import java.io.File
import java.io.IOException

/**
 * 录音（语音消息，开发计划 5.5）。接口便于在 ViewModel 测试中替换。
 */
interface VoiceRecorder {

    /**
     * 开始录音。
     *
     * @param file 输出文件（m4a）
     * @throws IOException 无法开始（没有录音权限、麦克风被占用）
     */
    fun start(file: File)

    /**
     * 停止录音并保存。
     *
     * @return 文件有效时为 true；录音太短没有数据等情况为 false（文件已删除）
     */
    fun stop(): Boolean

    /** 取消录音（删除文件）；没在录音时什么也不做 */
    fun cancel()
}

/**
 * MediaRecorder 实现：AAC-LC、16 kHz、单声道、32 kbps、MP4 容器（m4a），三端都能直接播放（协议规范 §7.3）。
 *
 * @property context 应用 Context
 */
class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {

    private val log = Logs.file(TAG)

    /** 正在使用的 MediaRecorder */
    private var recorder: MediaRecorder? = null

    /** 正在写入的文件 */
    private var output: File? = null

    /**
     * 开始录音。
     *
     * @param file 输出文件
     * @throws IOException 无法开始
     */
    override fun start(file: File) {
        cancel()
        file.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else newLegacyRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
        } catch (e: IOException) {
            r.release()
            file.delete()
            throw e
        } catch (e: RuntimeException) {
            // 没有权限、麦克风被占用时 setAudioSource / start 抛出 RuntimeException
            r.release()
            file.delete()
            throw IOException("无法开始录音", e)
        }
        recorder = r
        output = file
        log.info("开始录音：{}", file.name)
    }

    /**
     * 停止录音。
     *
     * @return 文件有效时为 true
     */
    override fun stop(): Boolean {
        val r = recorder ?: return false
        val file = output
        recorder = null
        output = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // 录音时间太短、没有采集到数据时 stop 抛出异常
            log.warn("停止录音失败：{}", e.toString())
            false
        } finally {
            r.release()
        }
        if (!ok) file?.delete()
        log.info("停止录音：{}，{} 字节，有效={}", file?.name, file?.length(), ok)
        return ok
    }

    /** 取消录音 */
    override fun cancel() {
        val r = recorder ?: return
        val file = output
        recorder = null
        output = null
        runCatching { r.stop() }
        r.release()
        file?.delete()
        log.info("取消录音")
    }

    /**
     * Android 12 以下的构造方法。
     *
     * @return MediaRecorder
     */
    @Suppress("DEPRECATION")
    private fun newLegacyRecorder(): MediaRecorder = MediaRecorder()

    companion object {
        private const val TAG = "VoiceRecorder"

        /** 采样率 */
        const val SAMPLE_RATE = 16_000

        /** 码率 */
        const val BIT_RATE = 32_000

        /** 语音文件的 MIME 类型 */
        const val MIME = "audio/mp4"
    }
}
