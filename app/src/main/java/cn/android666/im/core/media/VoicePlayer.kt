package cn.android666.im.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import cn.android666.im.core.log.Logs
import java.io.File
import java.io.IOException

/**
 * 语音播放（开发计划 5.5）。同一时间只播放一条。接口便于测试中替换。
 */
interface VoicePlayer {

    /**
     * 播放（正在播放的先停止）。
     *
     * @param file 语音文件
     * @param earpiece true 为听筒播放，false 为扬声器
     * @param onComplete 播放结束（正常结束或出错）时在主线程回调；调用 [stop] 停止时不回调
     * @throws IOException 文件无法播放
     */
    fun play(file: File, earpiece: Boolean, onComplete: () -> Unit)

    /** 停止播放；没在播放时什么也不做 */
    fun stop()
}

/**
 * MediaPlayer 实现。听筒播放时使用通话音频属性并把音频模式设为通话，结束后恢复。
 *
 * @property context 应用 Context
 */
class AndroidVoicePlayer(private val context: Context) : VoicePlayer {

    private val log = Logs.file(TAG)

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** 正在使用的播放器 */
    private var player: MediaPlayer? = null

    /** 当前是否在用听筒（结束时需要恢复音频模式） */
    private var usingEarpiece = false

    /**
     * 播放。
     *
     * @param file 语音文件
     * @param earpiece 是否听筒
     * @param onComplete 播放结束回调
     * @throws IOException 文件无法播放
     */
    override fun play(file: File, earpiece: Boolean, onComplete: () -> Unit) {
        stop()
        val usage = if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setDataSource(file.path)
            p.prepare()
        } catch (e: IOException) {
            p.release()
            throw e
        } catch (e: RuntimeException) {
            p.release()
            throw IOException("无法播放：${file.name}", e)
        }
        if (earpiece) routeToEarpiece()
        p.setOnCompletionListener {
            finish(it)
            onComplete()
        }
        p.setOnErrorListener { mp, what, extra ->
            log.warn("播放出错：what={}，extra={}", what, extra)
            finish(mp)
            onComplete()
            true
        }
        p.start()
        player = p
        log.info("开始播放语音：{}，听筒={}", file.name, earpiece)
    }

    /** 停止播放 */
    override fun stop() {
        val p = player ?: return
        runCatching { p.stop() }
        finish(p)
        log.info("停止播放语音")
    }

    /**
     * 释放播放器并恢复音频路由。
     *
     * @param p 播放器
     */
    private fun finish(p: MediaPlayer) {
        p.release()
        if (player === p) player = null
        if (usingEarpiece) {
            usingEarpiece = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager?.clearCommunicationDevice()
            audioManager?.mode = AudioManager.MODE_NORMAL
        }
    }

    /** 音频输出改到听筒 */
    @Suppress("DEPRECATION")
    private fun routeToEarpiece() {
        val manager = audioManager ?: return
        usingEarpiece = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?.let(manager::setCommunicationDevice)
        } else {
            manager.isSpeakerphoneOn = false
        }
    }

    private companion object {
        const val TAG = "VoicePlayer"
    }
}
