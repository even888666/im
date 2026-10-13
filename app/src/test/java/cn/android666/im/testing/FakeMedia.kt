package cn.android666.im.testing

import cn.android666.im.core.media.ImageProcessor
import cn.android666.im.core.media.PreparedImage
import cn.android666.im.core.media.ProcessedImage
import cn.android666.im.data.file.Uploader
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * 假的上传（媒体消息测试用）：按文件名返回下载地址；可以让某次上传挂起或失败。
 */
class FakeUploader : Uploader {
    /** 上传上限 */
    var limit: Long? = 10_000_000

    /** 已上传的文件名（按顺序） */
    val uploaded = mutableListOf<String>()

    /** 按文件名指定的闸门：上传挂起直到完成（异常完成时上传失败） */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** 下一次（任意文件）上传时抛出的异常 */
    val failures = ArrayDeque<Exception>()

    /** 闸门前报告的进度（百分比） */
    var progressBeforeGate = 0

    /**
     * 上传上限。
     *
     * @return [limit]
     */
    override fun uploadLimit(): Long? = limit

    /**
     * 上传。
     *
     * @param file 文件
     * @param originalName 原始文件名
     * @param mime 类型
     * @param onProgress 进度
     * @return `https://im.example.com/upload/{文件名}`
     */
    override suspend fun upload(file: File, originalName: String, mime: String, onProgress: (Long, Long) -> Unit): String {
        failures.removeFirstOrNull()?.let { throw it }
        gates[file.name]?.let { gate ->
            onProgress(progressBeforeGate.toLong(), 100)
            gate.await()
        }
        onProgress(100, 100)
        uploaded += file.name
        return "https://im.example.com/upload/${file.name}"
    }
}

/** 假的图片处理：主图 [MAIN_SIZE] 字节、缩略图 100 字节，尺寸固定；文件名 img{序号}.jpg / img{序号}_thumb.jpg */
class FakeImageProcessor : ImageProcessor {

    /** 已处理的张数 */
    private var count = 0

    /** 处理过的原图（按顺序） */
    val sources = mutableListOf<File>()

    /**
     * 生成主图和缩略图。
     *
     * @param source 原图
     * @param outDir 输出目录
     * @param original 是否原图
     * @return 结果
     */
    override fun prepare(source: File, outDir: File, original: Boolean): PreparedImage {
        sources += source
        outDir.mkdirs()
        val main = File(outDir, "img${++count}.jpg").apply { writeBytes(ByteArray(MAIN_SIZE)) }
        val thumb = File(outDir, "img${count}_thumb.jpg").apply { writeBytes(ByteArray(100)) }
        return PreparedImage(
            main = ProcessedImage(main, 1920, 1080, "image/jpeg"),
            thumbnail = ProcessedImage(thumb, 320, 180, "image/jpeg"),
            original = original,
        )
    }

    companion object {
        /** 主图大小 */
        const val MAIN_SIZE = 2000
    }
}
