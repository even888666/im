package cn.android666.im.ui.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.core.media.GallerySaver
import cn.android666.im.databinding.ActivityImagePreviewBinding
import cn.android666.im.ui.base.appViewModels
import cn.android666.im.ui.base.collectWhenStarted
import coil3.load
import coil3.request.placeholder
import coil3.size.Size
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 大图预览（开发计划 5.4）：先显示缩略图，原图下载完成后替换；双指缩放、双击放大；单击关闭；右下角保存到相册。
 */
class ImagePreviewActivity : AppCompatActivity() {

    private val log = Logs.file(TAG)

    private val viewModel by appViewModels { container ->
        val saver = GallerySaver(applicationContext, getString(R.string.app_name))
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
            saver = { file, name, mime -> withContext(container.dispatchers.io) { saver.save(file, name, mime) } },
        )
    }

    private lateinit var binding: ActivityImagePreviewBinding

    /** 已显示的原图（避免状态重复发出时重复加载） */
    private var shownFile: File? = null

    /** Android 9 及以下保存前申请存储权限 */
    private val requestStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.save() else Toast.makeText(this, R.string.preview_save_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.image.onTap = { finish() }
        // 原图加载前先显示缩略图
        viewModel.image.thumbnail?.let { thumb ->
            binding.image.load(if (thumb.startsWith("/")) File(thumb) else thumb)
        }
        binding.status.setOnClickListener { viewModel.retry() }
        binding.save.setOnClickListener { save() }
        collectWhenStarted(viewModel.state) { render(it) }
        collectWhenStarted(viewModel.toasts) { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 显示加载状态。
     *
     * @param state 状态
     */
    private fun render(state: PreviewState) {
        binding.save.isVisible = state is PreviewState.Ready
        binding.status.isVisible = state !is PreviewState.Ready
        when (state) {
            is PreviewState.Loading -> binding.status.text =
                getString(R.string.preview_downloading, state.percent.coerceAtLeast(0))
            PreviewState.Expired -> binding.status.setText(R.string.preview_expired)
            PreviewState.Failed -> binding.status.setText(R.string.preview_failed)
            is PreviewState.Ready -> if (shownFile != state.file) {
                shownFile = state.file
                val thumbnail = binding.image.drawable
                binding.image.load(state.file) {
                    // 放大后仍要清晰：按较大的固定尺寸解码（按比例缩小到不超过该尺寸），而不是控件尺寸
                    size(Size(MAX_DECODE_SIZE, MAX_DECODE_SIZE))
                    // 原图解码期间继续显示缩略图
                    placeholder(thumbnail)
                }
                log.info("显示原图：{}", state.file.name)
            }
        }
    }

    /** 保存到相册：Android 9 及以下需要存储权限 */
    private fun save() {
        val needPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needPermission) requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else viewModel.save()
    }

    companion object {
        private const val TAG = "ImagePreviewActivity"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_LOCAL_PATH = "localPath"
        private const val EXTRA_THUMBNAIL = "thumbnail"

        /** 没有文件名时保存用的名字 */
        private const val DEFAULT_NAME = "image.jpg"

        /** 没有类型时的默认类型 */
        private const val DEFAULT_MIME = "image/jpeg"

        /** 原图解码的最大边长（像素） */
        private const val MAX_DECODE_SIZE = 4096

        /**
         * 打开预览页的 Intent。
         *
         * @param context Context
         * @param image 要预览的图片
         * @return Intent
         */
        fun intent(context: Context, image: PreviewMedia): Intent = Intent(context, ImagePreviewActivity::class.java)
            .putExtra(EXTRA_URL, image.url)
            .putExtra(EXTRA_NAME, image.name)
            .putExtra(EXTRA_MIME, image.mime)
            .putExtra(EXTRA_SHA256, image.sha256)
            .putExtra(EXTRA_LOCAL_PATH, image.localPath)
            .putExtra(EXTRA_THUMBNAIL, image.thumbnail)
    }
}
