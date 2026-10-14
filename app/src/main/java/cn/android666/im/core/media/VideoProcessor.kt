package cn.android666.im.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import cn.android666.im.core.log.Logs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 视频的基本信息。
 *
 * @property width 宽（显示方向）
 * @property height 高（显示方向）
 * @property durationMillis 时长（毫秒）
 */
data class VideoInfo(val width: Int, val height: Int, val durationMillis: Long)

/**
 * 准备发送的视频。
 *
 * @property file 视频文件（H.264 + AAC，MP4）
 * @property info 视频信息
 * @property cover 封面（缩略图）
 */
data class PreparedVideo(val file: File, val info: VideoInfo, val cover: ProcessedImage)

/**
 * 发送视频前的处理（开发计划 5.7，协议规范 §7.4）。接口便于在 JVM 测试中替换。
 */
interface VideoProcessor {

    /**
     * 压缩视频并生成封面。
     *
     * @param source 用户选择的视频（已复制到本地）
     * @param outDir 输出目录
     * @param onProgress 压缩进度 0 ~ 100
     * @return 处理结果（文件都在 [outDir] 中）
     * @throws IOException 不是视频、无法解码或压缩失败
     */
    suspend fun prepare(source: File, outDir: File, onProgress: (Int) -> Unit): PreparedVideo
}

/**
 * Media3 Transformer 实现：长边 ≤ [MAX_LONG_SIDE]、H.264 + AAC、MP4，视频码率约 [VIDEO_BITRATE]；
 * 已经符合要求（H.264 + AAC、长边不超过上限）的 MP4 不再转码，直接复制；封面取第一帧，按图片缩略图规则压缩。
 *
 * Transformer 必须在创建它的 Looper 线程上调用，这里统一在主线程执行（转码本身在 Media3 的内部线程）。
 *
 * @property context 应用 Context
 * @property io IO 调度器（读取元数据、生成封面）
 */
@OptIn(UnstableApi::class)
class Media3VideoProcessor(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : VideoProcessor {

    private val log = Logs.file(TAG)

    /**
     * 压缩视频并生成封面。
     *
     * @param source 视频
     * @param outDir 输出目录
     * @param onProgress 压缩进度
     * @return 处理结果
     * @throws IOException 失败
     */
    override suspend fun prepare(source: File, outDir: File, onProgress: (Int) -> Unit): PreparedVideo {
        outDir.mkdirs()
        val base = UUID.randomUUID().toString()
        val original = withContext(io) { readInfo(source) }
        val target = File(outDir, "$base.mp4")
        val fitted = ImageSizing.fit(original.width, original.height, MAX_LONG_SIDE)
        if (withContext(io) { isCompliant(source, original) }) {
            withContext(io) { source.copyTo(target, overwrite = true) }
            log.info("视频已符合要求，不转码：{}×{}，{} 毫秒，{} 字节", original.width, original.height, original.durationMillis, source.length())
        } else {
            transcode(source, target, fitted, onProgress)
        }
        onProgress(PERCENT_DONE)
        val info = withContext(io) { readInfo(target) }
        val cover = withContext(io) { cover(target, File(outDir, "${base}_thumb.jpg")) }
        log.info(
            "视频处理完成：原 {}×{} {} 字节 → {}×{} {} 字节，{} 毫秒",
            original.width, original.height, source.length(), info.width, info.height, target.length(), info.durationMillis,
        )
        return PreparedVideo(target, info, cover)
    }

    /**
     * 用 Transformer 转码。
     *
     * @param source 原视频
     * @param target 输出
     * @param size 目标尺寸（显示方向）
     * @param onProgress 进度
     * @throws IOException 转码失败
     */
    private suspend fun transcode(source: File, target: File, size: Dimension, onProgress: (Int) -> Unit) = withContext(Dispatchers.Main) {
        coroutineScope {
            val encoder = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build())
                .build()
            lateinit var transformer: Transformer
            val poller = launch {
                val holder = ProgressHolder()
                while (true) {
                    delay(PROGRESS_INTERVAL_MILLIS)
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress.coerceIn(0, PERCENT_DONE - 1))
                }
            }
            try {
                suspendCancellableCoroutine { continuation ->
                    transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoder)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                target.delete()
                                if (continuation.isActive) continuation.resumeWithException(IOException("视频压缩失败：${exportException.errorCodeName}", exportException))
                            }
                        })
                        .build()
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT))))
                        .build()
                    log.info("开始压缩视频：目标 {}×{}", size.width, size.height)
                    transformer.start(item, target.path)
                    continuation.invokeOnCancellation {
                        // 取消回调可能不在主线程，且此时作用域已取消、不能再启动协程：直接投递到主线程
                        Handler(Looper.getMainLooper()).post { transformer.cancel() }
                        target.delete()
                    }
                }
            } finally {
                poller.cancel()
            }
        }
    }

    /**
     * 读取视频信息。
     *
     * @param file 视频
     * @return 信息（宽高为显示方向）
     * @throws IOException 不是视频
     */
    private fun readInfo(file: File): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (width <= 0 || height <= 0) throw IOException("无法识别的视频")
            val shown = ImageSizing.rotated(width, height, rotation)
            return VideoInfo(shown.width, shown.height, duration)
        } catch (e: RuntimeException) {
            throw IOException("无法读取视频", e)
        } finally {
            retriever.release()
        }
    }

    /**
     * 是否已经符合协议要求（MP4、H.264、AAC 或无音轨、长边不超过上限），符合时不再转码。
     *
     * @param file 视频
     * @param info 视频信息
     * @return 符合时为 true
     */
    private fun isCompliant(file: File, info: VideoInfo): Boolean {
        if (maxOf(info.width, info.height) > MAX_LONG_SIDE) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            val video = mimes.filter { it.startsWith("video/") }
            val audio = mimes.filter { it.startsWith("audio/") }
            file.extension.equals("mp4", ignoreCase = true) && video == listOf(MimeTypes.VIDEO_H264) &&
                audio.all { it == MimeTypes.AUDIO_AAC }
        } catch (e: IOException) {
            false
        } finally {
            extractor.release()
        }
    }

    /**
     * 生成封面：第一帧，按图片缩略图规则（长边 ≤ 320、≤ 30 KB）。
     *
     * @param video 视频
     * @param target 封面文件
     * @return 封面
     * @throws IOException 取不到画面
     */
    private fun cover(video: File, target: File): ProcessedImage {
        val retriever = MediaMetadataRetriever()
        val frame: Bitmap = try {
            retriever.setDataSource(video.path)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: throw IOException("无法取得视频画面")
        } catch (e: RuntimeException) {
            throw IOException("无法取得视频画面", e)
        } finally {
            retriever.release()
        }
        val (size, data) = ImageCompressor().thumbnailOf(frame)
        target.writeBytes(data)
        return ProcessedImage(target, size.width, size.height, ImageCompressor.MIME_JPEG)
    }

    companion object {
        private const val TAG = "VideoProcessor"

        /** 长边上限（协议规范 §7.4） */
        const val MAX_LONG_SIDE = 1280

        /** 视频码率（720p 足够清晰，1 分钟约 15 MB） */
        const val VIDEO_BITRATE = 2_000_000

        /** 进度查询间隔 */
        private const val PROGRESS_INTERVAL_MILLIS = 300L

        /** 完成的进度 */
        private const val PERCENT_DONE = 100
    }
}
