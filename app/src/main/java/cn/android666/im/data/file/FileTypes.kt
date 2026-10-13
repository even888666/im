package cn.android666.im.data.file

import java.util.Locale

/** 文件的大类（文件消息的图标颜色与标签，开发计划 5.6） */
enum class FileCategory {
    /** PDF */
    PDF,

    /** Word 文档 */
    WORD,

    /** Excel 表格 */
    EXCEL,

    /** PowerPoint 演示文稿 */
    POWERPOINT,

    /** 压缩包 */
    ARCHIVE,

    /** 纯文本 */
    TEXT,

    /** 图片 */
    IMAGE,

    /** 音频 */
    AUDIO,

    /** 视频 */
    VIDEO,

    /** Android 安装包 */
    APK,

    /** 其他 */
    OTHER,
}

/**
 * 文件类型与大小的显示规则（纯逻辑）。
 */
object FileTypes {

    /**
     * 文件大类：先看扩展名，没有可识别的扩展名时看 MIME 类型。
     *
     * @param name 文件名
     * @param mime MIME 类型
     * @return 大类
     */
    fun category(name: String, mime: String?): FileCategory {
        when (extension(name)) {
            "pdf" -> return FileCategory.PDF
            "doc", "docx", "wps", "rtf", "odt" -> return FileCategory.WORD
            "xls", "xlsx", "csv", "et", "ods" -> return FileCategory.EXCEL
            "ppt", "pptx", "dps", "odp" -> return FileCategory.POWERPOINT
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> return FileCategory.ARCHIVE
            "txt", "log", "md", "json", "xml" -> return FileCategory.TEXT
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> return FileCategory.IMAGE
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "amr" -> return FileCategory.AUDIO
            "mp4", "mov", "avi", "mkv", "3gp", "webm" -> return FileCategory.VIDEO
            "apk" -> return FileCategory.APK
        }
        val type = mime?.lowercase(Locale.ROOT).orEmpty()
        return when {
            type.startsWith("image/") -> FileCategory.IMAGE
            type.startsWith("audio/") -> FileCategory.AUDIO
            type.startsWith("video/") -> FileCategory.VIDEO
            type.startsWith("text/") -> FileCategory.TEXT
            type == "application/pdf" -> FileCategory.PDF
            else -> FileCategory.OTHER
        }
    }

    /**
     * 扩展名（小写，不含点）。
     *
     * @param name 文件名
     * @return 扩展名；没有时为空字符串
     */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /**
     * 文件大小的显示：1023 B、12 KB、1.2 MB、1.05 GB。
     *
     * @param bytes 字节数
     * @return 文字
     */
    fun formatSize(bytes: Long): String = when {
        bytes < KB -> "$bytes B"
        bytes < MB -> "${(bytes + KB / 2) / KB} KB"
        bytes < GB -> String.format(Locale.ROOT, "%.1f MB", bytes / MB.toDouble())
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / GB.toDouble())
    }

    /**
     * 保存到本地时安全的文件名：去掉路径分隔符和控制字符，过长时保留扩展名截断；空名字时用"未命名"。
     *
     * @param name 原始文件名（来自对方的消息，不可信）
     * @return 文件名
     */
    fun safeName(name: String): String {
        var cleaned = name.map { if (it == '/' || it == '\\' || it.code < 0x20 || it == '\u007f') '_' else it }.joinToString("").trim()
        // 不能是 "." 或 ".."（路径穿越）
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) cleaned = UNNAMED
        if (cleaned.length <= MAX_NAME) return cleaned
        val ext = extension(cleaned).take(MAX_EXT)
        val keep = MAX_NAME - (if (ext.isEmpty()) 0 else ext.length + 1)
        return cleaned.substring(0, keep) + if (ext.isEmpty()) "" else ".$ext"
    }

    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024

    /** 文件名最大长度（字符） */
    private const val MAX_NAME = 100

    /** 截断时保留的扩展名最大长度 */
    private const val MAX_EXT = 10

    /** 没有名字时的文件名 */
    private const val UNNAMED = "未命名"
}
