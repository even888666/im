package cn.android666.im.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 以挂起方式执行请求；协程取消时取消请求（上传 / 下载大文件时可以中途取消）。
 *
 * @receiver 请求
 * @return 响应（调用方负责关闭）
 * @throws IOException 网络错误或被取消
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}

/**
 * 上传文件的请求体，写出时回调进度。
 *
 * @property file 文件
 * @property mediaType 类型
 * @property onProgress 进度回调（已写出字节数、总字节数），在 OkHttp 的线程中调用
 */
class ProgressFileBody(
    private val file: File,
    private val mediaType: MediaType?,
    private val onProgress: (written: Long, total: Long) -> Unit,
) : RequestBody() {

    /**
     * 类型。
     *
     * @return MIME 类型
     */
    override fun contentType(): MediaType? = mediaType

    /**
     * 长度。
     *
     * @return 字节数
     */
    override fun contentLength(): Long = file.length()

    /**
     * 写出文件内容并回调进度。
     *
     * @param sink 输出
     */
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                onProgress(written, total)
            }
        }.buffer()
        file.source().use { counting.writeAll(it) }
        counting.flush()
    }
}
