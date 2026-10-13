package cn.android666.im.ui.media

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.data.file.ChecksumMismatchException
import cn.android666.im.data.file.Downloader
import cn.android666.im.data.file.FileExpiredException
import cn.android666.im.data.file.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** 大图的加载状态 */
sealed interface PreviewState {

    /**
     * 正在下载原图（先显示缩略图）。
     *
     * @property percent 进度 0 ~ 100；总大小未知时为 -1
     */
    data class Loading(val percent: Int) : PreviewState

    /**
     * 原图已在本地。
     *
     * @property file 文件
     */
    data class Ready(val file: File) : PreviewState

    /** 服务器上已不存在（过期清理，HTTP 404） */
    data object Expired : PreviewState

    /** 下载失败（网络等），可重试 */
    data object Failed : PreviewState
}

/**
 * 要预览的图片。
 *
 * @property url 原图下载地址（还没上传完时为空）
 * @property name 原始文件名（保存到相册时使用）
 * @property mime MIME 类型
 * @property sha256 SHA-256（消息中带了时校验）
 * @property localPath 原图的本地文件（本人发出的）
 * @property thumbnail 缩略图（本地文件路径或下载地址），原图加载完成前显示
 */
data class PreviewImage(
    val url: String,
    val name: String,
    val mime: String,
    val sha256: String?,
    val localPath: String?,
    val thumbnail: String?,
)

/**
 * 大图预览（开发计划 5.4）：本地有原图（本人发出的、之前看过的）直接显示；没有时先显示缩略图并下载原图到媒体缓存；
 * 404 提示"已过期"；可保存到相册。
 *
 * @property image 要预览的图片
 * @property downloader 下载
 * @property cache 当前账号的媒体缓存；未登录时为 null（只能显示本地文件）
 * @property saver 保存到相册（文件、文件名、MIME 类型），失败时抛出 IOException
 */
class ImagePreviewViewModel(
    val image: PreviewImage,
    private val downloader: Downloader,
    private val cache: MediaCache?,
    private val saver: suspend (File, String, String) -> Unit,
) : ViewModel() {

    private val log = Logs.file(TAG)

    private val stateFlow = MutableStateFlow<PreviewState>(PreviewState.Loading(0))

    /** 加载状态 */
    val state: StateFlow<PreviewState> = stateFlow.asStateFlow()

    private val eventChannel = Channel<Int>(Channel.BUFFERED)

    /** 一次性提示（字符串资源） */
    val toasts: Flow<Int> = eventChannel.receiveAsFlow()

    /** 正在进行的下载 */
    private var loadJob: Job? = null

    init {
        load()
    }

    /** 加载原图 */
    private fun load() {
        loadJob = viewModelScope.launch {
            image.localPath?.let(::File)?.takeIf { it.exists() }?.let {
                stateFlow.value = PreviewState.Ready(it)
                return@launch
            }
            if (image.url.isEmpty()) {
                stateFlow.value = PreviewState.Failed
                return@launch
            }
            cache?.get(image.url, image.name)?.let {
                stateFlow.value = PreviewState.Ready(it)
                return@launch
            }
            val target = cache?.fileFor(image.url, image.name)
            if (target == null) {
                stateFlow.value = PreviewState.Failed
                return@launch
            }
            stateFlow.value = PreviewState.Loading(0)
            stateFlow.value = try {
                val file = downloader.download(image.url, target, image.sha256) { done, total ->
                    stateFlow.value = PreviewState.Loading(if (total > 0) (done * 100 / total).toInt() else -1)
                }
                PreviewState.Ready(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileExpiredException) {
                log.info("原图已过期：{}", image.url)
                PreviewState.Expired
            } catch (e: ChecksumMismatchException) {
                log.warn("原图校验失败：{}，{}", image.url, e.message)
                PreviewState.Failed
            } catch (e: IOException) {
                log.warn("原图下载失败：{}，{}", image.url, e.toString())
                PreviewState.Failed
            }
        }
    }

    /** 下载失败后点击重试 */
    fun retry() {
        if (stateFlow.value != PreviewState.Failed || loadJob?.isActive == true) return
        load()
    }

    /** 保存到相册（原图已在本地时） */
    fun save() {
        val file = (stateFlow.value as? PreviewState.Ready)?.file ?: return
        viewModelScope.launch {
            @StringRes val message = try {
                saver(file, image.name, image.mime)
                R.string.preview_saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("保存到相册失败：{}", image.name, e)
                R.string.preview_save_failed
            }
            eventChannel.send(message)
        }
    }

    private companion object {
        const val TAG = "ImagePreview"
    }
}
