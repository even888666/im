package cn.android666.im.core.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cn.android666.im.core.log.Logs
import java.io.File
import java.io.IOException

/**
 * 保存图片到系统相册（开发计划 5.4）：放在"Pictures/{应用名}"下。
 *
 * - Android 10 及以上：通过 MediaStore 写入，不需要存储权限；
 * - Android 9 及以下：写入公共图片目录并通知媒体扫描，需要 WRITE_EXTERNAL_STORAGE 权限（由界面申请）。
 *
 * @property context 应用 Context
 * @property albumName 相册目录名（应用名）
 */
class GallerySaver(private val context: Context, private val albumName: String) {

    private val log = Logs.file(TAG)

    /**
     * 保存。
     *
     * @param source 图片文件
     * @param displayName 保存的文件名（同名时系统或本方法自动加序号）
     * @param mime MIME 类型
     * @throws IOException 写入失败（空间不足、没有权限）
     */
    fun save(source: File, displayName: String, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(source, displayName, mime) else saveLegacy(source, displayName)
        log.info("已保存到相册：{}，{} 字节", displayName, source.length())
    }

    /**
     * Android 10 及以上：MediaStore（写入期间标记为待定，其他应用看不到半截文件）。
     *
     * @param source 图片文件
     * @param displayName 文件名
     * @param mime MIME 类型
     */
    private fun saveWithMediaStore(source: File, displayName: String, mime: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$albumName")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("无法创建相册文件")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("无法写入相册文件")
            output.use { out -> source.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Android 9 及以下：写入公共图片目录并通知媒体扫描。
     *
     * @param source 图片文件
     * @param displayName 文件名
     */
    @Suppress("DEPRECATION")
    private fun saveLegacy(source: File, displayName: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), albumName)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目录：$dir")
        val target = uniqueFile(dir, displayName)
        source.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
    }

    companion object {
        private const val TAG = "GallerySaver"

        /**
         * 目录中不重名的文件：重名时在扩展名前加"(1)""(2)"……
         *
         * @param dir 目录
         * @param name 文件名
         * @return 文件
         */
        fun uniqueFile(dir: File, name: String): File {
            var file = File(dir, name)
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var n = 1
            while (file.exists()) file = File(dir, "$base(${n++})$ext")
            return file
        }
    }
}
