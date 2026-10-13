package cn.android666.im.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cn.android666.im.core.log.Logs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 从其他应用导入的文件（已复制到本地）。
 *
 * @property file 本地副本（调用方用完后删除）
 * @property displayName 原始文件名；对方没提供时为 null
 * @property mime MIME 类型；对方没提供时为 null
 */
data class ImportedFile(val file: File, val displayName: String?, val mime: String?)

/**
 * 把用户选择的内容（相册、文件管理器、相机返回的 Uri）复制到本地。接口便于在 ViewModel 测试中替换。
 */
interface MediaImporter {

    /**
     * 导入。
     *
     * @param uri 内容地址（content:// 或 file://）
     * @return 本地副本
     * @throws IOException 无法读取（权限失效、文件已删除）
     */
    suspend fun import(uri: Uri): ImportedFile
}

/**
 * 通过 ContentResolver 导入（开发计划 5.4）：相册等返回的 Uri 只有短期读取权限，必须先复制一份再慢慢处理、上传。
 *
 * @property context 应用 Context
 * @property dir 存放副本的目录（缓存目录下）
 * @property io IO 调度器
 */
class UriImporter(
    private val context: Context,
    private val dir: File,
    private val io: CoroutineDispatcher,
) : MediaImporter {

    private val log = Logs.file(TAG)

    /**
     * 导入。
     *
     * @param uri 内容地址
     * @return 本地副本
     * @throws IOException 无法读取
     */
    override suspend fun import(uri: Uri): ImportedFile = withContext(io) {
        val resolver = context.contentResolver
        val name = displayName(uri)
        // 其他应用的 ContentProvider 可能抛出各种运行时异常，类型只是参考，取不到不影响导入
        val mime = try {
            resolver.getType(uri)
        } catch (e: RuntimeException) {
            log.debug("查询类型失败：{}，{}", uri, e.toString())
            null
        }
        dir.mkdirs()
        val target = File(dir, UUID.randomUUID().toString())
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("无法打开：$uri")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        } catch (e: SecurityException) {
            target.delete()
            throw IOException("没有读取权限：$uri", e)
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        log.info("已导入：{}，{} 字节，类型={}", name, target.length(), mime)
        ImportedFile(target, name, mime)
    }

    /**
     * 查询原始文件名。
     *
     * @param uri 内容地址
     * @return 文件名；查不到时为 null
     */
    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (e: RuntimeException) {
            log.debug("查询文件名失败：{}，{}", uri, e.toString())
            null
        }
    }

    private companion object {
        const val TAG = "UriImporter"
    }
}
