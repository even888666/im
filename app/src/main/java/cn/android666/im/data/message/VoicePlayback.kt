package cn.android666.im.data.message

import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.MediaFileEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.media.VoicePlayer
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.file.Downloader
import cn.android666.im.data.file.FileExpiredException
import cn.android666.im.data.file.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 语音播放失败的原因 */
enum class VoiceError {
    /** 服务器上已不存在（过期清理） */
    EXPIRED,

    /** 下载或播放失败 */
    FAILED,
}

/**
 * 语音消息播放（开发计划 5.5）：
 * - 点击语音：本地没有时先下载到媒体缓存，再播放；再次点击正在播放的语音则停止；同一时间只播放一条；
 * - 对方发来的语音播放后标记已听（去掉红点）；播放完一条未听的语音后自动播放该会话中下一条未听的语音（重听已听过的不连播）；
 * - 听筒 / 扬声器由设置决定。
 *
 * @property player 播放器（主线程调用）
 * @property downloader 下载
 * @property cache 当前账号的媒体缓存；未登录时为 null
 * @property databases 当前账号的用户数据库
 * @property earpiece 是否听筒播放
 * @property scope 协程作用域（主线程，播放器回调也在主线程）
 * @property io IO 调度器（下载）
 */
class VoicePlayback(
    private val player: VoicePlayer,
    private val downloader: Downloader,
    private val cache: () -> MediaCache?,
    private val databases: UserDatabaseHolder,
    private val earpiece: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {

    private val log = Logs.file(TAG)

    private val playingFlow = MutableStateFlow<Long?>(null)

    /** 正在播放的语音 rowId（包括正在下载准备播放的） */
    val playing: StateFlow<Long?> = playingFlow.asStateFlow()

    private val errorFlow = MutableSharedFlow<VoiceError>(extraBufferCapacity = ERROR_BUFFER)

    /** 播放失败（界面提示） */
    val errors: SharedFlow<VoiceError> = errorFlow.asSharedFlow()

    /** 正在准备（下载）的任务 */
    private var job: Job? = null

    /**
     * 点击一条语音：正在播放它则停止，否则播放它（停止其他的）。
     *
     * @param rowId 消息 rowId
     */
    fun toggle(rowId: Long) {
        if (playingFlow.value == rowId) {
            stop()
            return
        }
        play(rowId)
    }

    /** 停止播放（离开聊天页、开始录音时） */
    fun stop() {
        job?.cancel()
        job = null
        if (playingFlow.value != null) {
            player.stop()
            playingFlow.value = null
        }
    }

    /**
     * 播放一条语音。
     *
     * @param rowId 消息 rowId
     */
    private fun play(rowId: Long) {
        stop()
        playingFlow.value = rowId
        job = scope.launch {
            val db = databases.require()
            val message = db.messageDao().getByRowId(rowId)
            val voice = message?.let { MessageJson.decodeContent(it.type, it.contentJson) } as? MessageContent.Voice
            if (message == null || voice == null || message.retracted) {
                playingFlow.value = null
                return@launch
            }
            val file = try {
                localFile(rowId, voice) ?: download(voice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileExpiredException) {
                log.info("语音已过期：msgId={}", message.msgId)
                fail(VoiceError.EXPIRED)
                return@launch
            } catch (e: IOException) {
                log.warn("语音下载失败：msgId={}，{}", message.msgId, e.toString())
                fail(VoiceError.FAILED)
                return@launch
            }
            if (playingFlow.value != rowId) return@launch
            try {
                player.play(file, earpiece()) { onComplete(message) }
            } catch (e: IOException) {
                log.warn("语音播放失败：msgId={}，{}", message.msgId, e.toString())
                fail(VoiceError.FAILED)
                return@launch
            }
            if (message.direction == MessageEntity.DIRECTION_IN && !message.voicePlayed) db.messageDao().markVoicePlayed(rowId)
        }
    }

    /**
     * 播放结束：对方发来的、播放前未听的语音结束后自动播放下一条未听的。
     *
     * @param message 刚播放完的消息（播放前从数据库读取的，voicePlayed 为播放前的状态）
     */
    private fun onComplete(message: MessageEntity) {
        if (playingFlow.value != message.rowId) return
        playingFlow.value = null
        // 只有播放的是未听的语音时才继续播放后面未听的（点开已听过的语音重听时不连播）
        if (message.direction != MessageEntity.DIRECTION_IN || message.voicePlayed) return
        scope.launch {
            val next = databases.require().messageDao().nextUnplayedVoice(message.conversationId, message.serverTime, message.rowId)
            if (next != null && playingFlow.value == null) {
                log.info("自动播放下一条未听语音：msgId={}", next.msgId)
                play(next.rowId)
            }
        }
    }

    /**
     * 播放失败。
     *
     * @param error 原因
     */
    private fun fail(error: VoiceError) {
        playingFlow.value = null
        errorFlow.tryEmit(error)
    }

    /**
     * 本地已有的语音文件：本人发出的（附件记录中的本地文件）或之前下载过的（媒体缓存）。
     *
     * @param rowId 消息 rowId
     * @param voice 语音内容
     * @return 文件；没有时为 null
     */
    private suspend fun localFile(rowId: Long, voice: MessageContent.Voice): File? {
        databases.require().mediaFileDao().get(rowId, MediaFileEntity.ROLE_ORIGINAL)?.localPath?.let(::File)?.takeIf { it.exists() }?.let { return it }
        if (voice.file.url.isEmpty()) return null
        return withContext(io) { cache()?.get(voice.file.url, voice.file.name) }
    }

    /**
     * 下载语音到媒体缓存。
     *
     * @param voice 语音内容
     * @return 文件
     * @throws IOException 下载失败、没有地址、未登录
     */
    private suspend fun download(voice: MessageContent.Voice): File {
        if (voice.file.url.isEmpty()) throw IOException("语音还没有上传完")
        val target = cache()?.fileFor(voice.file.url, voice.file.name) ?: throw IOException("未登录")
        return withContext(io) { downloader.download(voice.file.url, target, voice.file.sha256) }
    }

    private companion object {
        const val TAG = "VoicePlayback"

        /** 错误通知的缓冲 */
        const val ERROR_BUFFER = 4
    }
}
