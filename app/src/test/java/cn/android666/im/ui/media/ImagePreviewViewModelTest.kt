package cn.android666.im.ui.media

import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.data.file.Downloader
import cn.android666.im.data.file.FileExpiredException
import cn.android666.im.data.file.MediaCache
import cn.android666.im.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** [ImagePreviewViewModel] 测试：本地 / 缓存 / 下载、过期、失败重试、保存 */
class ImagePreviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temp = TemporaryFolder()

    /** 假的下载：按队列返回结果（写入目标文件或抛出异常），可挂起 */
    private class FakeDownloader : Downloader {
        /** 下载结果：null 表示成功 */
        val results = ArrayDeque<Exception?>()

        /** 下载前的闸门 */
        var gate: CompletableDeferred<Unit>? = null

        /** 下载次数 */
        var calls = 0

        override suspend fun download(url: String, target: File, sha256: String?, onProgress: (Long, Long) -> Unit): File {
            calls++
            onProgress(30, 100)
            gate?.await()
            results.removeFirstOrNull()?.let { throw it }
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(1, 2))
            return target
        }
    }

    private val downloader = FakeDownloader()

    /** 保存到相册的记录 */
    private val saved = mutableListOf<Triple<File, String, String>>()

    /** 保存时抛出的异常 */
    private var saveError: Exception? = null

    /**
     * 被测对象。
     *
     * @param url 原图地址
     * @param localPath 本地原图
     * @param cache 媒体缓存
     * @return ViewModel
     */
    private fun vm(url: String = URL, localPath: String? = null, cache: MediaCache? = MediaCache(temp.root)) = ImagePreviewViewModel(
        PreviewImage(url, "a.jpg", "image/jpeg", null, localPath, null),
        downloader,
        cache,
    ) { file, name, mime ->
        saveError?.let { throw it }
        saved += Triple(file, name, mime)
    }

    @Test
    fun localFile_readyWithoutDownload() = runTest(mainDispatcherRule.testDispatcher) {
        val local = temp.newFile("mine.jpg")
        val vm = vm(localPath = local.path)
        runCurrent()
        assertThat(vm.state.value).isEqualTo(PreviewState.Ready(local))
        assertThat(downloader.calls).isEqualTo(0)
    }

    @Test
    fun cached_readyWithoutDownload() = runTest(mainDispatcherRule.testDispatcher) {
        val cache = MediaCache(temp.root)
        val file = cache.fileFor(URL, "a.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        val vm = vm(cache = cache)
        runCurrent()
        assertThat(vm.state.value).isEqualTo(PreviewState.Ready(file))
        assertThat(downloader.calls).isEqualTo(0)
    }

    @Test
    fun download_progressThenReady() = runTest(mainDispatcherRule.testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        downloader.gate = gate
        val vm = vm()
        runCurrent()
        assertThat(vm.state.value).isEqualTo(PreviewState.Loading(30))

        gate.complete(Unit)
        runCurrent()
        assertThat(vm.state.value).isEqualTo(PreviewState.Ready(MediaCache(temp.root).fileFor(URL, "a.jpg")))
    }

    @Test
    fun notFound_expired() = runTest(mainDispatcherRule.testDispatcher) {
        downloader.results += FileExpiredException(URL)
        val vm = vm()
        runCurrent()
        assertThat(vm.state.value).isEqualTo(PreviewState.Expired)
        // 过期不能重试
        vm.retry()
        runCurrent()
        assertThat(downloader.calls).isEqualTo(1)
    }

    @Test
    fun networkError_failedThenRetry() = runTest(mainDispatcherRule.testDispatcher) {
        downloader.results += IOException("断网")
        val vm = vm()
        runCurrent()
        assertThat(vm.state.value).isEqualTo(PreviewState.Failed)

        vm.retry()
        runCurrent()
        assertThat(vm.state.value).isInstanceOf(PreviewState.Ready::class.java)
        assertThat(downloader.calls).isEqualTo(2)
    }

    /** 还没上传完（没有地址）且本地没有原图：失败 */
    @Test
    fun noUrl_failed() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = vm(url = "")
        runCurrent()
        assertThat(vm.state.value).isEqualTo(PreviewState.Failed)
        assertThat(downloader.calls).isEqualTo(0)
    }

    @Test
    fun save_success_and_failure() = runTest(mainDispatcherRule.testDispatcher) {
        val local = temp.newFile("mine.jpg")
        val vm = vm(localPath = local.path)
        runCurrent()

        vm.toasts.test {
            vm.save()
            assertThat(awaitItem()).isEqualTo(R.string.preview_saved)
            assertThat(saved.single()).isEqualTo(Triple(local, "a.jpg", "image/jpeg"))

            saveError = IOException("空间不足")
            vm.save()
            assertThat(awaitItem()).isEqualTo(R.string.preview_save_failed)
        }
    }

    /** 原图还没加载好时不能保存 */
    @Test
    fun save_beforeReady_ignored() = runTest(mainDispatcherRule.testDispatcher) {
        downloader.gate = CompletableDeferred()
        val vm = vm()
        runCurrent()
        vm.save()
        runCurrent()
        assertThat(saved).isEmpty()
    }

    private companion object {
        const val URL = "https://im.example.com/upload/a.jpg"
    }
}
