package cn.android666.im.data.file

import cn.android666.im.core.log.Logs
import cn.android666.im.core.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 文件已过期或被服务器清理（HTTP 404，协议规范 §9） */
class FileExpiredException(url: String) : IOException("文件已过期或被清理：$url")

/** 下载的文件与消息中的 SHA-256 不一致 */
class ChecksumMismatchException(expected: String, actual: String) : IOException("文件校验失败：期望 $expected，实际 $actual")

/**
 * 文件下载（开发计划 5.3、5.6"断点续传"）：先写到 `{目标}.part`，完成并校验后改名为目标文件。
 *
 * - 已有未完成的 `.part` 时用 `Range` 续传；服务器不支持续传（返回 200）时从头下载；
 * - HTTP 404 → [FileExpiredException]（界面提示"文件已过期或被清理"）；
 * - 消息带 sha256 时校验，不一致时删除并抛出 [ChecksumMismatchException]；
 * - 协程取消时中止下载，保留 `.part` 以便下次续传。
 *
 * @property http HTTP 客户端（只信任企业根证书）
 */
class FileDownloader(private val http: OkHttpClient) {

    private val log = Logs.file(TAG)

    /**
     * 下载。
     *
     * @param url 下载地址
     * @param target 目标文件（已存在时直接返回，不重复下载）
     * @param sha256 期望的 SHA-256（十六进制，大小写不限）；为 null 时不校验
     * @param onProgress 进度回调（已下载字节数、总字节数；总数未知时为 -1）
     * @return 目标文件
     * @throws FileExpiredException HTTP 404
     * @throws ChecksumMismatchException 校验失败
     * @throws IOException 其他网络 / 写文件错误
     */
    suspend fun download(url: String, target: File, sha256: String? = null, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        val part = File(target.path + PART_SUFFIX)
        val resumeFrom = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url(url).apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }.build()
        http.newCall(request).await().use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> throw FileExpiredException(url)
                // 续传的起点超出文件（.part 已经完整或文件已变化）：从头下载
                response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                    part.delete()
                    return download(url, target, sha256, onProgress)
                }
                !response.isSuccessful -> throw IOException("下载失败：HTTP ${response.code}")
            }
            val append = resumeFrom > 0 && response.code == HTTP_PARTIAL
            val offset = if (append) resumeFrom else 0L
            val total = response.body.contentLength().let { if (it >= 0) it + offset else -1L }
            if (resumeFrom > 0) log.info("续传：从 {} 字节开始，服务器{}支持", resumeFrom, if (append) "" else "不")
            withContext(Dispatchers.IO) {
                FileOutputStream(part, append).use { out ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = offset
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
            }
        }
        if (sha256 != null) {
            val actual = withContext(Dispatchers.IO) { sha256Of(part) }
            if (!actual.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw ChecksumMismatchException(sha256, actual)
            }
        }
        if (!part.renameTo(target)) throw IOException("无法保存下载的文件：$target")
        log.info("下载完成：{}，{} 字节", target.name, target.length())
        return target
    }

    companion object {
        private const val TAG = "FileDownloader"

        /** 未完成下载的后缀 */
        const val PART_SUFFIX = ".part"

        private const val HTTP_PARTIAL = 206
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** 读写缓冲区大小 */
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * 计算文件的 SHA-256。
         *
         * @param file 文件
         * @return 十六进制小写
         */
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
