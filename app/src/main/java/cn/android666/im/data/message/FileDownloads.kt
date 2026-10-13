package cn.android666.im.data.message

import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.MediaFileEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.file.ChecksumMismatchException
import cn.android666.im.data.file.Downloader
import cn.android666.im.data.file.FileExpiredException
import cn.android666.im.data.file.FileTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件消息的下载（开发计划 5.6）：点击未下载的文件开始下载，下载中再点击暂停（保留已下载的部分，下次续传）。
 *
 * - 状态与进度写在附件表（media_file，原文件一行），聊天页随之刷新；
 * - 文件保存在应用文件目录 `files/{工号}/{地址哈希}/{原始文件名}`（不会被系统自动清理，保留原名方便用其他应用打开，决策记录 A126）；
 * - 404 → "已过期"；SHA-256 不一致 → 失败（已删除）；网络错误 → 失败，可重试（续传）；
 * - 应用被杀时还在下载的，下次打开用户数据库时改为"已暂停"（不自动继续，避免意外消耗流量）。
 *
 * @property databases 当前账号的用户数据库
 * @property downloader 下载
 * @property filesRoot 当前账号的文件目录；未登录时为 null
 * @property scope 应用级协程作用域（下载不随界面销毁而停止）
 * @property io IO 调度器
 */
class FileDownloads(
    private val databases: UserDatabaseHolder,
    private val downloader: Downloader,
    private val filesRoot: () -> File?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {

    private val log = Logs.file(TAG)

    /** 正在下载的消息 rowId → 任务 */
    private val jobs = ConcurrentHashMap<Long, Job>()

    init {
        // 只在数据库真的换了（切换账号、退出登录）时取消：首次收到的就是当前数据库，此时可能已有刚开始的下载
        val initial = databases.database.value
        scope.launch {
            var previous = initial
            databases.database.collect { db ->
                if (db !== previous) {
                    previous = db
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                }
                if (db != null) pauseInterrupted(db)
            }
        }
    }

    /**
     * 点击文件：没在下载时开始（或继续）下载，正在下载时暂停。
     *
     * @param rowId 消息 rowId
     */
    fun toggle(rowId: Long) {
        val running = jobs[rowId]
        if (running != null && running.isActive) {
            log.info("暂停下载：rowId={}", rowId)
            running.cancel()
            return
        }
        val db = databases.database.value ?: return
        jobs[rowId] = scope.launch(io) {
            try {
                download(db, rowId)
            } finally {
                jobs.remove(rowId, coroutineContext[Job])
            }
        }
    }

    /**
     * 是否正在下载。
     *
     * @param rowId 消息 rowId
     * @return 正在下载时为 true
     */
    fun isDownloading(rowId: Long): Boolean = jobs[rowId]?.isActive == true

    /**
     * 下载文件并更新状态。
     *
     * @param db 数据库
     * @param rowId 消息 rowId
     */
    private suspend fun download(db: ImDatabase, rowId: Long) {
        val message = db.messageDao().getByRowId(rowId) ?: return
        val info = (MessageJson.decodeContent(message.type, message.contentJson) as? MessageContent.File)?.file ?: return
        if (info.url.isEmpty()) return
        val root = filesRoot() ?: return
        val target = targetFor(root, info.url, info.name)
        val dao = db.mediaFileDao()
        val existing = dao.get(rowId, MediaFileEntity.ROLE_ORIGINAL)
        val record = (existing ?: MediaFileEntity(msgRowId = rowId, role = MediaFileEntity.ROLE_ORIGINAL, state = MediaFileEntity.STATE_DOWNLOADING))
            .copy(url = info.url, localPath = target.path, state = MediaFileEntity.STATE_DOWNLOADING)
        dao.upsert(record)
        val id = dao.get(rowId, MediaFileEntity.ROLE_ORIGINAL)?.id ?: return
        log.info("开始下载文件：msgId={}，{}，{} 字节", message.msgId, info.name, info.size)
        val result = try {
            downloadWithProgress(db, id, info.url, target, info.sha256)
            log.info("文件下载完成：msgId={}，{}", message.msgId, info.name)
            MediaFileEntity.STATE_DONE
        } catch (e: CancellationException) {
            // 暂停：保留 .part 和进度，下次续传
            withContext(NonCancellable) { dao.setState(id, MediaFileEntity.STATE_NOT_DOWNLOADED, currentProgress(dao, rowId)) }
            throw e
        } catch (e: FileExpiredException) {
            log.info("文件已过期：msgId={}", message.msgId)
            MediaFileEntity.STATE_EXPIRED
        } catch (e: ChecksumMismatchException) {
            log.warn("文件校验失败：msgId={}，{}", message.msgId, e.message)
            MediaFileEntity.STATE_FAILED
        } catch (e: IOException) {
            log.warn("文件下载失败：msgId={}，{}", message.msgId, e.toString())
            MediaFileEntity.STATE_FAILED
        }
        dao.setState(id, result, if (result == MediaFileEntity.STATE_DONE) 100 else currentProgress(dao, rowId))
    }

    /**
     * 下载并把进度写入数据库（每增加 [PROGRESS_STEP] 个百分点写一次）。
     *
     * @param db 数据库
     * @param fileId 附件 ID
     * @param url 下载地址
     * @param target 目标文件
     * @param sha256 期望的 SHA-256
     */
    private suspend fun downloadWithProgress(db: ImDatabase, fileId: Long, url: String, target: File, sha256: String?) = coroutineScope {
        val progress = MutableStateFlow(-1)
        val writer = launch { progress.collect { if (it >= 0) db.mediaFileDao().setProgress(fileId, it) } }
        try {
            downloader.download(url, target, sha256) { done, total ->
                if (total > 0) {
                    val percent = (done * 100 / total).toInt()
                    if (percent < 100) progress.value = percent / PROGRESS_STEP * PROGRESS_STEP
                }
            }
        } finally {
            writer.cancel()
        }
    }

    /**
     * 当前记录的进度。
     *
     * @param dao 附件表
     * @param rowId 消息 rowId
     * @return 进度
     */
    private suspend fun currentProgress(dao: cn.android666.im.core.database.dao.MediaFileDao, rowId: Long): Int =
        dao.get(rowId, MediaFileEntity.ROLE_ORIGINAL)?.progress ?: 0

    /**
     * 上次没下载完的改为"已暂停"。
     *
     * @param db 数据库
     */
    private suspend fun pauseInterrupted(db: ImDatabase) {
        try {
            val dao = db.mediaFileDao()
            val rowIds = dao.messagesInState(MediaFileEntity.STATE_DOWNLOADING)
            // 本次运行中正在下载的不动
            rowIds.filterNot(::isDownloading).forEach { rowId ->
                dao.get(rowId, MediaFileEntity.ROLE_ORIGINAL)?.let { dao.setState(it.id, MediaFileEntity.STATE_NOT_DOWNLOADED, it.progress) }
            }
            if (rowIds.isNotEmpty()) log.info("上次未完成的下载改为已暂停：{} 个", rowIds.size)
        } catch (e: IllegalStateException) {
            log.warn("读取未完成的下载失败（数据库已关闭）", e)
        }
    }

    companion object {
        private const val TAG = "FileDownloads"

        /** 进度写库的间隔（百分点） */
        private const val PROGRESS_STEP = 5

        /**
         * 文件的保存位置：`{root}/{地址 SHA-1 前 16 位}/{安全的原始文件名}`。
         *
         * @param root 当前账号的文件目录
         * @param url 下载地址
         * @param name 原始文件名
         * @return 目标文件
         */
        fun targetFor(root: File, url: String, name: String): File {
            val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
            return File(File(root, hash), FileTypes.safeName(name))
        }

        /** 目录名中地址哈希的长度 */
        private const val HASH_LENGTH = 16
    }
}
