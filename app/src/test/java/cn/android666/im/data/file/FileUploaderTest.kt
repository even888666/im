package cn.android666.im.data.file

import cn.android666.im.core.xmpp.UploadSlot
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.testing.FakeXmppClient
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
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** [FileUploader] 测试：MockWebServer 作为上传服务，FakeXmppClient 分配槽位 */
class FileUploaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val client = FakeXmppClient()
    private var slotSeq = 0

    @Before
    fun setUp() {
        server.start()
        // 每次申请槽位返回新的上传地址
        client.slotResponder = { name, _, _ ->
            slotSeq++
            UploadSlot(server.url("/put/$slotSeq/$name").toString(), "https://im.example.com:7443/get/$slotSeq/$name", mapOf("X-Token" to "t$slotSeq"))
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun uploader(max: Long? = 1_000_000) =
        FileUploader(client, OkHttpClient(), maxFileSize = { max }, uuid = { "u-${slotSeq + 1}" })

    private fun file(bytes: Int): File = tempFolder.newFile().apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    @Test
    fun upload_success_withProgressHeadersAndUuidName() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).build())
        val progress = mutableListOf<Pair<Long, Long>>()

        val url = uploader().upload(file(20_000), "季度报告.PDF", "application/pdf") { sent, total -> progress += sent to total }

        assertThat(url).isEqualTo("https://im.example.com:7443/get/1/u-1.pdf")
        assertThat(client.slotRequests.single()).isEqualTo(Triple("u-1.pdf", 20_000L, "application/pdf"))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.headers["X-Token"]).isEqualTo("t1")
        assertThat(request.headers["Content-Type"]).startsWith("application/pdf")
        assertThat(request.body!!.size).isEqualTo(20_000)
        assertThat(progress.last()).isEqualTo(20_000L to 20_000L)
        assertThat(progress.map { it.first }).isInStrictOrder()
    }

    /** 失败重试：每次重新申请槽位；第 3 次成功 */
    @Test
    fun retry_newSlotEachTime() = runBlocking {
        // 连接断开时 OkHttp 会自己重试同一请求，这里用 HTTP 错误模拟需要重新申请槽位的失败
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(200).build())

        val url = uploader().upload(file(10), "a.jpg", "image/jpeg")

        assertThat(client.slotRequests).hasSize(3)
        assertThat(url).contains("/get/3/")
    }

    /** 3 次都失败：抛出 UploadFailedException */
    @Test
    fun retry_givesUpAfter3() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(500).build()) }

        val e = assertThrows(UploadFailedException::class.java) { runBlocking { uploader().upload(file(10), "a.jpg", "image/jpeg") } }
        assertThat(e.message).contains("3")
        assertThat(client.slotRequests).hasSize(3)
    }

    /** 超过上限：不申请槽位；没有上传服务：失败 */
    @Test
    fun tooLarge_or_noService() {
        val tooLarge = assertThrows(FileTooLargeException::class.java) { runBlocking { uploader(max = 100).upload(file(101), "a.bin", "x/y") } }
        assertThat(tooLarge.maxSize).isEqualTo(100)
        assertThat(client.slotRequests).isEmpty()

        assertThrows(UploadFailedException::class.java) { runBlocking { uploader(max = null).upload(file(1), "a.bin", "x/y") } }
    }

    /** 服务器拒绝分配槽位（stanza error）：不重试 */
    @Test
    fun slotRejected_noRetry() {
        client.slotResponder = { _, _, _ -> throw XmppException.StanzaError("not-acceptable", null) }

        assertThrows(UploadFailedException::class.java) { runBlocking { uploader().upload(file(1), "a.bin", "x/y") } }
        assertThat(client.slotRequests).hasSize(1)
    }

    /** 协程取消：中止上传请求 */
    @Test
    fun cancel_abortsUpload() = runBlocking {
        // 服务器很慢地读取请求体
        server.enqueue(MockResponse.Builder().throttleBody(1, 1, TimeUnit.SECONDS).build())
        val big = file(5_000_000)
        val job = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { uploader(max = 10_000_000).upload(big, "big.bin", "application/octet-stream") }
        }
        delay(300)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun uploadName_extension() {
        val uploader = FileUploader(client, OkHttpClient(), { 1 }, uuid = { "u" })
        assertThat(uploader.uploadName("a.JPG")).isEqualTo("u.jpg")
        assertThat(uploader.uploadName("archive.tar.gz")).isEqualTo("u.gz")
        assertThat(uploader.uploadName("无扩展名")).isEqualTo("u")
        assertThat(uploader.uploadName("点结尾.")).isEqualTo("u")
        assertThat(uploader.uploadName("奇怪.扩展/名")).isEqualTo("u")
    }
}
