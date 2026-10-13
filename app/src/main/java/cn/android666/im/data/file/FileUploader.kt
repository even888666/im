package cn.android666.im.data.file

import cn.android666.im.core.log.Logs
import cn.android666.im.core.network.ProgressFileBody
import cn.android666.im.core.network.await
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** 文件超过服务器的上传上限（协议规范 §9：超过时客户端直接提示，不申请槽位） */
class FileTooLargeException(val size: Long, val maxSize: Long) : Exception("文件 $size 字节，超过上限 $maxSize 字节")

/** 上传失败（重试后仍失败） */
class UploadFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 文件上传的抽象（媒体消息发送使用，测试中替换为假实现）。
 */
interface Uploader {

    /**
     * 服务器的上传上限。
     *
     * @return 字节数；没有上传服务时为 null
     */
    fun uploadLimit(): Long?

    /**
     * 上传。
     *
     * @param file 本地文件
     * @param originalName 原始文件名（取扩展名）
     * @param mime MIME 类型
     * @param onProgress 进度回调（已上传字节数、总字节数）
     * @return 下载地址
     * @throws FileTooLargeException 超过上限
     * @throws UploadFailedException 没有上传服务、或重试后仍失败
     */
    suspend fun upload(file: File, originalName: String, mime: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): String
}

/**
 * 文件上传（开发计划 5.2，协议规范 §9，XEP-0363）：申请槽位 → HTTP PUT 上传（带进度）→ 返回下载地址。
 *
 * - 上传用的文件名改为 `{UUID}.{扩展名}`，原始文件名放在消息的 file@name 中；
 * - 超过服务器上限时直接失败（[FileTooLargeException]），不申请槽位；
 * - 失败时最多重试 [MAX_ATTEMPTS] 次，每次重新申请槽位；协程取消时中止上传。
 *
 * @property client XMPP 客户端（申请槽位）
 * @property http HTTP 客户端（只信任企业根证书）
 * @property maxFileSize 服务器的上传上限（字节）；没有上传服务时为 null
 * @property uuid 生成上传文件名中的 UUID
 */
class FileUploader(
    private val client: XmppClient,
    private val http: OkHttpClient,
    private val maxFileSize: () -> Long?,
    private val uuid: () -> String = { UUID.randomUUID().toString() },
) : Uploader {

    private val log = Logs.file(TAG)

    /**
     * 服务器的上传上限。
     *
     * @return 字节数；没有上传服务时为 null
     */
    override fun uploadLimit(): Long? = maxFileSize()

    /**
     * 上传。
     *
     * @param file 本地文件
     * @param originalName 原始文件名（取扩展名）
     * @param mime MIME 类型
     * @param onProgress 进度回调（已上传字节数、总字节数）
     * @return 下载地址
     * @throws FileTooLargeException 超过上限
     * @throws UploadFailedException 没有上传服务、或重试后仍失败
     */
    override suspend fun upload(file: File, originalName: String, mime: String, onProgress: (Long, Long) -> Unit): String {
        val size = file.length()
        val max = maxFileSize() ?: throw UploadFailedException("服务器没有文件上传服务")
        if (size > max) throw FileTooLargeException(size, max)
        val uploadName = uploadName(originalName)
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val slot = client.requestUploadSlot(uploadName, size, mime)
                val request = Request.Builder()
                    .url(slot.putUrl)
                    .apply { slot.headers.forEach { (name, value) -> header(name, value) } }
                    .put(ProgressFileBody(file, mime.toMediaTypeOrNull(), onProgress))
                    .build()
                http.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        log.info("上传成功：{}，{} 字节，第 {} 次", uploadName, size, attempt)
                        return slot.getUrl
                    }
                    lastError = UploadFailedException("HTTP ${response.code}")
                    log.warn("上传失败（第 {} 次）：HTTP {}", attempt, response.code)
                }
            } catch (e: CancellationException) {
                log.info("上传已取消：{}", uploadName)
                throw e
            } catch (e: XmppException.StanzaError) {
                // 服务器拒绝分配槽位（如没有上传服务、超过上限），重试没有意义
                throw UploadFailedException("申请上传槽位被拒绝：${e.condition}", e)
            } catch (e: XmppException) {
                lastError = e
                log.warn("申请上传槽位失败（第 {} 次）：{}", attempt, e.message)
            } catch (e: IOException) {
                lastError = e
                log.warn("上传失败（第 {} 次）：{}", attempt, e.toString())
            }
        }
        throw UploadFailedException("上传失败，已重试 $MAX_ATTEMPTS 次", lastError)
    }

    /**
     * 上传用的文件名：`{UUID}.{扩展名}`（扩展名转小写；没有扩展名时只有 UUID）。
     *
     * @param originalName 原始文件名
     * @return 文件名
     */
    internal fun uploadName(originalName: String): String {
        val ext = originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXT_LENGTH && it.all(Char::isLetterOrDigit) }
        return if (ext == null) uuid() else "${uuid()}.$ext"
    }

    companion object {
        private const val TAG = "FileUploader"

        /** 最多尝试次数（协议规范 §9：失败可重试 3 次） */
        const val MAX_ATTEMPTS = 3

        /** 扩展名最长长度（更长的视为没有扩展名） */
        private const val MAX_EXT_LENGTH = 10
    }
}
