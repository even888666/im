package cn.android666.im.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.databinding.ActivityVideoPlayerBinding
import cn.android666.im.ui.base.appViewModels
import cn.android666.im.ui.base.collectWhenStarted
import coil3.load
import java.io.File

/**
 * 视频播放（开发计划 5.7）：本地没有时先显示封面并下载视频到媒体缓存（显示进度，404 提示已过期），下载完成后用 ExoPlayer 播放。
 * 页面不可见时暂停，销毁时释放播放器。
 */
class VideoPlayerActivity : AppCompatActivity() {

    private val log = Logs.file(TAG)

    private val viewModel by appViewModels { container ->
        MediaPreviewViewModel(
            image = PreviewMedia(
                url = intent.getStringExtra(EXTRA_URL).orEmpty(),
                name = intent.getStringExtra(EXTRA_NAME) ?: DEFAULT_NAME,
                mime = intent.getStringExtra(EXTRA_MIME) ?: DEFAULT_MIME,
                sha256 = intent.getStringExtra(EXTRA_SHA256),
                localPath = intent.getStringExtra(EXTRA_LOCAL_PATH),
                thumbnail = intent.getStringExtra(EXTRA_THUMBNAIL),
            ),
            downloader = container.fileDownloader,
            cache = container.mediaCache(),
            // 视频页不提供保存
            saver = { _, _, _ -> throw UnsupportedOperationException("视频不支持保存到相册") },
        )
    }

    private lateinit var binding: ActivityVideoPlayerBinding

    /** 播放器（视频就绪后创建） */
    private var player: ExoPlayer? = null

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.close.setOnClickListener { finish() }
        viewModel.image.thumbnail?.let { thumb -> binding.cover.load(if (thumb.startsWith("/")) File(thumb) else thumb) }
        binding.status.setOnClickListener { viewModel.retry() }
        collectWhenStarted(viewModel.state, ::render)
    }

    /**
     * 显示加载状态；视频就绪后开始播放。
     *
     * @param state 状态
     */
    private fun render(state: PreviewState) {
        binding.status.isVisible = state !is PreviewState.Ready
        when (state) {
            is PreviewState.Loading -> binding.status.text = getString(R.string.preview_downloading, state.percent.coerceAtLeast(0))
            PreviewState.Expired -> binding.status.setText(R.string.chat_video_expired)
            PreviewState.Failed -> binding.status.setText(R.string.preview_failed)
            is PreviewState.Ready -> play(state.file)
        }
    }

    /**
     * 播放本地视频（已在播放时不重复创建）。
     *
     * @param file 视频文件
     */
    private fun play(file: File) {
        if (player != null) return
        val exo = ExoPlayer.Builder(this).build()
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        binding.player.player = exo
        binding.player.isVisible = true
        binding.cover.isVisible = false
        log.info("播放视频：{}", file.name)
    }

    /** 页面不可见：暂停 */
    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    /** 销毁：释放播放器 */
    override fun onDestroy() {
        binding.player.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_LOCAL_PATH = "localPath"
        private const val EXTRA_THUMBNAIL = "thumbnail"

        /** 没有文件名时的名字 */
        private const val DEFAULT_NAME = "video.mp4"

        /** 没有类型时的默认类型 */
        private const val DEFAULT_MIME = "video/mp4"

        /**
         * 打开播放页的 Intent。
         *
         * @param context Context
         * @param video 要播放的视频
         * @return Intent
         */
        fun intent(context: Context, video: PreviewMedia): Intent = Intent(context, VideoPlayerActivity::class.java)
            .putExtra(EXTRA_URL, video.url)
            .putExtra(EXTRA_NAME, video.name)
            .putExtra(EXTRA_MIME, video.mime)
            .putExtra(EXTRA_SHA256, video.sha256)
            .putExtra(EXTRA_LOCAL_PATH, video.localPath)
            .putExtra(EXTRA_THUMBNAIL, video.thumbnail)
    }
}
