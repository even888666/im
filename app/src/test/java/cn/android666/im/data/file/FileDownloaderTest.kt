package cn.android666.im.data.file

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** [FileDownloader] 测试：MockWebServer 模拟下载服务 */
class FileDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val downloader = FileDownloader(OkHttpClient())
    private val content = ByteArray(100_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun target(): File = File(tempFolder.root, "media/a.bin")

    @Test
    fun download_withProgressAndChecksum() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(content)).build())
        val progress = mutableListOf<Pair<Long, Long>>()

        val file = downloader.download(server.url("/f").toString(), target(), FileDownloader.sha256Of(tempFolder.newFile().apply { writeBytes(content) })) { d, t ->
            progress += d to t
        }

        assertThat(file.readBytes()).isEqualTo(content)
        assertThat(progress.last()).isEqualTo(100_000L to 100_000L)
        assertThat(File(file.path + FileDownloader.PART_SUFFIX).exists()).isFalse()
    }

    /** 本地已有：不再请求 */
    @Test
    fun existing_noRequest() = runBlocking {
        val target = target().apply {
            parentFile!!.mkdirs()
            writeText("已有")
        }
        assertThat(downloader.download(server.url("/f").toString(), target).readText()).isEqualTo("已有")
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** 404：文件已过期 */
    @Test
    fun notFound_expired() {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertThrows(FileExpiredException::class.java) { runBlocking { downloader.download(server.url("/f").toString(), target()) } }
        assertThat(target().exists()).isFalse()
    }

    /** 断点续传：服务器返回 206 时追加 */
    @Test
    fun resume_partialContent() = runBlocking {
        val target = target()
        target.parentFile!!.mkdirs()
        File(target.path + FileDownloader.PART_SUFFIX).writeBytes(content.copyOfRange(0, 40_000))
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 40000-99999/100000")
                .body(Buffer().write(content.copyOfRange(40_000, 100_000)))
                .build(),
        )

        downloader.download(server.url("/f").toString(), target)

        assertThat(server.takeRequest().headers["Range"]).isEqualTo("bytes=40000-")
        assertThat(target.readBytes()).isEqualTo(content)
    }

    /** 服务器不支持续传（返回 200 全量）：覆盖重写 */
    @Test
    fun resume_notSupported_overwrites() = runBlocking {
        val target = target()
        target.parentFile!!.mkdirs()
        File(target.path + FileDownloader.PART_SUFFIX).writeBytes(ByteArray(10) { 9 })
        server.enqueue(MockResponse.Builder().body(Buffer().write(content)).build())

        downloader.download(server.url("/f").toString(), target)

        assertThat(target.readBytes()).isEqualTo(content)
    }

    /** 416（续传起点超出文件）：删除 .part 从头下载 */
    @Test
    fun rangeNotSatisfiable_restart() = runBlocking {
        val target = target()
        target.parentFile!!.mkdirs()
        File(target.path + FileDownloader.PART_SUFFIX).writeBytes(ByteArray(200_000))
        server.enqueue(MockResponse.Builder().code(416).build())
        server.enqueue(MockResponse.Builder().body(Buffer().write(content)).build())

        downloader.download(server.url("/f").toString(), target)

        assertThat(target.readBytes()).isEqualTo(content)
        server.takeRequest()
        assertThat(server.takeRequest().headers["Range"]).isNull()
    }

    /** 校验失败：删除并抛出 */
    @Test
    fun checksumMismatch() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(content)).build())
        assertThrows(ChecksumMismatchException::class.java) {
            runBlocking { downloader.download(server.url("/f").toString(), target(), sha256 = "00".repeat(32)) }
        }
        assertThat(target().exists()).isFalse()
        assertThat(File(target().path + FileDownloader.PART_SUFFIX).exists()).isFalse()
    }

    /** 取消：中止下载，保留 .part 以便续传 */
    @Test
    fun cancel_keepsPart() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(content)).throttleBody(10_000, 200, TimeUnit.MILLISECONDS).build())
        val job = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { downloader.download(server.url("/f").toString(), target()) }
        }
        delay(500)
        job.cancel()
        withTimeout(5_000) { job.join() }

        assertThat(target().exists()).isFalse()
        val part = File(target().path + FileDownloader.PART_SUFFIX)
        assertThat(part.exists()).isTrue()
        assertThat(part.length()).isLessThan(content.size.toLong())
    }

    @Test
    fun sha256Of() {
        val file = tempFolder.newFile().apply { writeText("abc") }
        assertThat(FileDownloader.sha256Of(file)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
