package cn.android666.im.data.file

import cn.android666.im.core.log.Logs
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 媒体文件的本地缓存（开发计划 5.3）：按下载地址的哈希命名（同一地址只下载一次），按账号分目录。
 *
 * - 缩略图、图片、语音等可以重新下载的放在缓存目录（系统空间不足时可能被清理）；
 * - 用户下载的文件（5.6）放在应用文件目录，不会被系统自动清理；
 * - [trimTo] 按最近使用时间删除最旧的文件，直到总大小不超过上限（存储管理 M8 中手动清理也用它）。
 *
 * @property root 缓存根目录（如 `{cacheDir}/media/{工号}`）
 */
class MediaCache(private val root: File) {

    private val log = Logs.file(TAG)

    /**
     * 某个下载地址对应的本地文件（不一定存在）。
     *
     * @param url 下载地址
     * @param name 原始文件名（只取扩展名，便于其他应用识别类型）
     * @return 本地文件
     */
    fun fileFor(url: String, name: String = url.substringAfterLast('/')): File {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && it.length <= MAX_EXT && it.all(Char::isLetterOrDigit) }
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, if (ext == null) hash else "$hash.$ext")
    }

    /**
     * 本地已有时返回文件并更新最近使用时间；没有时为 null。
     *
     * @param url 下载地址
     * @param name 原始文件名
     * @return 本地文件
     */
    fun get(url: String, name: String = url.substringAfterLast('/')): File? =
        fileFor(url, name).takeIf { it.exists() }?.also { it.setLastModified(System.currentTimeMillis()) }

    /**
     * 缓存总大小（不含未完成的下载）。
     *
     * @return 字节数
     */
    fun size(): Long = files().sumOf { it.length() }

    /**
     * 按最近使用时间删除最旧的文件，直到总大小不超过上限。
     *
     * @param maxBytes 上限
     * @return 删除的文件数
     */
    fun trimTo(maxBytes: Long): Int {
        var total = size()
        if (total <= maxBytes) return 0
        var deleted = 0
        for (file in files().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
                deleted++
            }
        }
        log.info("清理媒体缓存：删除 {} 个文件，剩余 {} 字节", deleted, total)
        return deleted
    }

    /** 删除全部缓存（包括未完成的下载） */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
        log.info("已清空媒体缓存：{}", root)
    }

    /**
     * 已完成的缓存文件。
     *
     * @return 文件列表
     */
    private fun files(): List<File> = root.listFiles()?.filter { it.isFile && !it.name.endsWith(FileDownloader.PART_SUFFIX) }.orEmpty()

    private companion object {
        const val TAG = "MediaCache"

        /** 扩展名最长长度 */
        const val MAX_EXT = 10
    }
}
