package cn.android666.im.data.message

import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.MediaFileEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.media.ImageProcessor
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.extension.msg.FileInfo
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.extension.msg.ThumbnailInfo
import cn.android666.im.data.file.FileDownloader
import cn.android666.im.data.file.FileTooLargeException
import cn.android666.im.data.file.FileTypes
import cn.android666.im.data.file.MediaCache
import cn.android666.im.data.file.UploadFailedException
import cn.android666.im.data.file.Uploader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 媒体消息发送（开发计划 5.4，Android工程架构.md §6.2）：处理文件 → 以"发送中"写库（界面立即显示，附件为"上传中"）→
 * 上传缩略图 → 上传原文件（带进度）→ 填入下载地址 → 交给发送队列发送消息。
 *
 * - 两次上传都成功后才发送消息（协议规范 §7.2）；附件上传完之前发送队列不会发送它（MessageDao.pending）；
 * - 上传失败 → 消息"发送失败"（原因见 [SendError]），用户点重发时只重传没传完的附件；
 * - 未连接时最多等待 [connectWaitMillis]，仍未连上 → 失败（与文本消息一致，决策记录 A73）；
 * - 应用被杀时还在上传的，下次打开用户数据库时自动继续；切换账号时取消；
 * - 上传完成的文件移入媒体缓存（按下载地址命名），预览、保存时直接使用，不再下载。
 *
 * @property client XMPP 客户端（等待连接）
 * @property databases 当前账号的用户数据库
 * @property repository 消息仓库
 * @property uploader 文件上传
 * @property images 图片处理
 * @property workDir 待上传文件的目录（按账号）；未登录时为 null
 * @property cache 当前账号的媒体缓存；未登录时为 null
 * @property scope 应用级协程作用域（上传不随界面销毁而取消）
 * @property io IO 调度器（图片处理、文件操作）
 * @property connectWaitMillis 未连接时最长等待时间
 */
class MediaSender(
    private val client: XmppClient,
    private val databases: UserDatabaseHolder,
    private val repository: MessageRepository,
    private val uploader: Uploader,
    private val images: ImageProcessor,
    private val workDir: () -> File?,
    private val cache: () -> MediaCache?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val connectWaitMillis: Long = CONNECT_WAIT_MILLIS,
) {

    private val log = Logs.file(TAG)

    /** 正在上传的消息 rowId → 上传任务 */
    private val jobs = ConcurrentHashMap<Long, Job>()

    init {
        // 每次打开（或切换）用户数据库：取消上一个账号的上传，继续本账号上次没传完的
        // 只在数据库真的换了（切换账号、退出登录）时取消：首次收到的就是当前数据库，此时可能已有刚开始的上传
        val initial = databases.database.value
        scope.launch {
            var previous = initial
            databases.database.collect { db ->
                if (db !== previous) {
                    previous = db
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                }
                if (db != null) resumeUploads(db)
            }
        }
    }

    /**
     * 发送图片（协议规范 §7.2）。
     *
     * @param conversationId 会话 ID
     * @param conversationType 会话类型（会话不存在时用于创建）
     * @param source 用户选择的图片（已复制到本地，调用方负责删除）
     * @param displayName 原始文件名（如 IMG_001.jpg）；不知道时为 null
     * @param original 是否发送原图
     * @return 写入的消息
     * @throws IOException 不是图片或无法读取
     * @throws FileTooLargeException 超过服务器的上传上限（此时不写入消息）
     * @throws IllegalStateException 未登录
     */
    suspend fun sendImage(
        conversationId: String,
        conversationType: Int,
        source: File,
        displayName: String?,
        original: Boolean,
    ): MessageEntity {
        val db = databases.require()
        val dir = workDir() ?: throw IllegalStateException("未登录")
        val prepared = withContext(io) { images.prepare(source, dir, original) }
        val limit = uploader.uploadLimit()
        val size = prepared.main.file.length()
        if (limit != null && size > limit) {
            prepared.main.file.delete()
            prepared.thumbnail.file.delete()
            log.info("图片 {} 字节超过上限 {}，不发送", size, limit)
            throw FileTooLargeException(size, limit)
        }
        val ext = prepared.main.file.extension
        val base = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: prepared.main.file.nameWithoutExtension
        val content = MessageContent.Image(
            file = FileInfo(
                url = "",
                name = "$base.$ext",
                size = size,
                mime = prepared.main.mime,
                width = prepared.main.width,
                height = prepared.main.height,
            ),
            thumbnail = ThumbnailInfo(url = "", width = prepared.thumbnail.width, height = prepared.thumbnail.height, mime = prepared.thumbnail.mime),
            original = prepared.original,
        )
        val attachments = listOf(
            MediaFileEntity(msgRowId = 0, role = MediaFileEntity.ROLE_ORIGINAL, localPath = prepared.main.file.path, state = MediaFileEntity.STATE_UPLOADING),
            MediaFileEntity(msgRowId = 0, role = MediaFileEntity.ROLE_THUMBNAIL, localPath = prepared.thumbnail.file.path, state = MediaFileEntity.STATE_UPLOADING),
        )
        val saved = repository.enqueueMedia(conversationId, conversationType, content, attachments)
        log.info("图片消息已写入，开始上传：msgId={}，会话={}，{} 字节，原图={}", saved.msgId, conversationId, size, original)
        start(db, saved.rowId)
        return saved
    }

    /**
     * 发送语音（协议规范 §7.3）：录音文件移入待上传目录后上传、发送。
     *
     * @param conversationId 会话 ID
     * @param conversationType 会话类型
     * @param source 录音文件（m4a，会被移走）
     * @param durationMillis 时长（毫秒），应在 [VoiceRules.MIN_MILLIS] ~ [VoiceRules.MAX_MILLIS] 之间
     * @return 写入的消息
     * @throws IOException 文件无法移动
     * @throws IllegalStateException 未登录
     */
    suspend fun sendVoice(conversationId: String, conversationType: Int, source: File, durationMillis: Long): MessageEntity {
        val db = databases.require()
        val dir = workDir() ?: throw IllegalStateException("未登录")
        val file = withContext(io) {
            dir.mkdirs()
            val target = File(dir, "${UUID.randomUUID()}.m4a")
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            target
        }
        val duration = durationMillis.coerceIn(VoiceRules.MIN_MILLIS, VoiceRules.MAX_MILLIS)
        val content = MessageContent.Voice(FileInfo(url = "", name = VOICE_NAME, size = file.length(), mime = VOICE_MIME, duration = duration))
        val attachments = listOf(
            MediaFileEntity(msgRowId = 0, role = MediaFileEntity.ROLE_ORIGINAL, localPath = file.path, state = MediaFileEntity.STATE_UPLOADING),
        )
        val saved = repository.enqueueMedia(conversationId, conversationType, content, attachments)
        log.info("语音消息已写入，开始上传：msgId={}，会话={}，{} 毫秒，{} 字节", saved.msgId, conversationId, duration, file.length())
        start(db, saved.rowId)
        return saved
    }

    /**
     * 发送文件（协议规范 §7.5）：导入的副本移入待上传目录，计算 SHA-256（接收方下载后校验），上传后发送。
     *
     * @param conversationId 会话 ID
     * @param conversationType 会话类型
     * @param source 文件（已复制到本地，会被移走）
     * @param displayName 原始文件名；不知道时为 null（用"未命名"）
     * @param mime MIME 类型；不知道时为 null（按 application/octet-stream）
     * @return 写入的消息
     * @throws FileTooLargeException 超过服务器的上传上限（此时不写入消息，源文件保留由调用方删除）
     * @throws IOException 文件无法读取或移动
     * @throws IllegalStateException 未登录
     */
    suspend fun sendFile(conversationId: String, conversationType: Int, source: File, displayName: String?, mime: String?): MessageEntity {
        val db = databases.require()
        val dir = workDir() ?: throw IllegalStateException("未登录")
        val size = source.length()
        val limit = uploader.uploadLimit()
        if (limit != null && size > limit) {
            log.info("文件 {} 字节超过上限 {}，不发送", size, limit)
            throw FileTooLargeException(size, limit)
        }
        val name = FileTypes.safeName(displayName ?: "")
        val (file, sha256) = withContext(io) {
            dir.mkdirs()
            val ext = FileTypes.extension(name).let { if (it.isEmpty()) "" else ".$it" }
            val target = File(dir, "${UUID.randomUUID()}$ext")
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            target to FileDownloader.sha256Of(target)
        }
        val content = MessageContent.File(
            FileInfo(url = "", name = name, size = size, mime = mime?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME, sha256 = sha256),
        )
        val attachments = listOf(
            MediaFileEntity(msgRowId = 0, role = MediaFileEntity.ROLE_ORIGINAL, localPath = file.path, state = MediaFileEntity.STATE_UPLOADING),
        )
        val saved = repository.enqueueMedia(conversationId, conversationType, content, attachments)
        log.info("文件消息已写入，开始上传：msgId={}，会话={}，{}，{} 字节", saved.msgId, conversationId, name, size)
        start(db, saved.rowId)
        return saved
    }

    /**
     * 重发一条发送失败的消息：附件没传完的重新上传；附件都已上传（是消息本身没发出去）的交给发送队列。
     *
     * @param rowId 消息 rowId
     * @return 是否已重新开始；消息不是发送失败状态时为 false
     */
    suspend fun resend(rowId: Long): Boolean {
        val db = databases.require()
        val files = db.mediaFileDao().forMessage(rowId)
        if (files.all { it.state == MediaFileEntity.STATE_DONE }) return repository.resend(rowId)
        if (db.messageDao().markResend(rowId) == 0) return false
        files.filter { it.state != MediaFileEntity.STATE_DONE }.forEach {
            db.mediaFileDao().setState(it.id, MediaFileEntity.STATE_UPLOADING, 0)
        }
        log.info("重新上传附件：rowId={}", rowId)
        start(db, rowId)
        return true
    }

    /**
     * 继续上次没传完的附件（应用被杀、进程重启）。
     *
     * @param db 数据库
     */
    private suspend fun resumeUploads(db: ImDatabase) {
        val rowIds = try {
            db.mediaFileDao().messagesInState(MediaFileEntity.STATE_UPLOADING)
        } catch (e: IllegalStateException) {
            log.warn("读取未完成的上传失败（数据库已关闭）", e)
            return
        }
        if (rowIds.isEmpty()) return
        log.info("继续上次未完成的上传：{} 条消息", rowIds.size)
        rowIds.forEach { start(db, it) }
    }

    /**
     * 启动一条消息的上传任务（已在上传时不重复启动）。
     *
     * @param db 数据库
     * @param rowId 消息 rowId
     */
    private fun start(db: ImDatabase, rowId: Long) {
        jobs.compute(rowId) { _, existing ->
            existing?.takeIf { it.isActive } ?: scope.launch(io) {
                try {
                    upload(db, rowId)
                } finally {
                    jobs.remove(rowId, coroutineContext[Job])
                }
            }
        }
    }

    /**
     * 上传一条消息的附件（缩略图在前），全部完成后唤醒发送队列。
     *
     * @param db 数据库
     * @param rowId 消息 rowId
     */
    private suspend fun upload(db: ImDatabase, rowId: Long) {
        val message = db.messageDao().getByRowId(rowId)
        val files = db.mediaFileDao().forMessage(rowId)
        if (message == null || message.status != MessageEntity.STATUS_SENDING) {
            // 消息已删除，或状态不对（不应出现）：附件标记失败，避免每次启动都尝试
            files.filter { it.state == MediaFileEntity.STATE_UPLOADING }.forEach { db.mediaFileDao().setState(it.id, MediaFileEntity.STATE_FAILED, 0) }
            log.warn("消息不在发送中，不上传：rowId={}，状态={}", rowId, message?.status)
            return
        }
        if (!awaitConnected()) {
            fail(db, message, files, SendError.NOT_CONNECTED)
            return
        }
        var content = MessageJson.decodeContent(message.type, message.contentJson)
        try {
            // 缩略图（role = 1）先传：对方收到消息时先显示缩略图
            for (file in files.sortedByDescending { it.role }) {
                if (file.state == MediaFileEntity.STATE_DONE && file.url != null) continue
                val local = file.localPath?.let(::File)?.takeIf { it.exists() } ?: throw MissingFileException(file.localPath)
                val mime = content.mimeOf(file.role)
                val url = uploadWithProgress(db, file.id, local, mime)
                val stored = moveToCache(local, url)
                db.mediaFileDao().markUploaded(file.id, url, stored.path)
                content = content.withUrl(file.role, url)
                db.messageDao().updateContent(rowId, MessageJson.encodeContent(content).orEmpty())
            }
        } catch (e: FileTooLargeException) {
            log.warn("附件超过上限：msgId={}，{}", message.msgId, e.message)
            fail(db, message, files, SendError.FILE_TOO_LARGE)
            return
        } catch (e: UploadFailedException) {
            log.warn("附件上传失败：msgId={}，{}", message.msgId, e.message)
            fail(db, message, files, SendError.UPLOAD_FAILED)
            return
        } catch (e: MissingFileException) {
            log.warn("附件的本地文件已不存在：msgId={}，{}", message.msgId, e.message)
            fail(db, message, files, SendError.FILE_MISSING)
            return
        } catch (e: IllegalStateException) {
            // 数据库已关闭（切换账号、退出登录）
            log.info("上传中止（数据库已关闭）：msgId={}", message.msgId)
            return
        }
        log.info("附件全部上传完成，交给发送队列：msgId={}", message.msgId)
        repository.wakeSendQueue()
    }

    /**
     * 上传一个文件并把进度写入数据库：每增加 [PROGRESS_STEP] 个百分点才写一次（每次写库都会让聊天页重新加载一页），
     * 写库在单独的协程中进行（进度回调在 OkHttp 线程中，不能阻塞）。
     *
     * @param db 数据库
     * @param fileId 附件 ID
     * @param local 本地文件
     * @param mime MIME 类型
     * @return 下载地址
     */
    private suspend fun uploadWithProgress(db: ImDatabase, fileId: Long, local: File, mime: String): String = coroutineScope {
        val progress = MutableStateFlow(0)
        val writer = launch { progress.collect { if (it > 0) db.mediaFileDao().setProgress(fileId, it) } }
        try {
            uploader.upload(local, local.name, mime) { sent, total ->
                if (total > 0) {
                    val percent = (sent * 100 / total).toInt()
                    // 只按整档更新，StateFlow 去重后最多写 100 / PROGRESS_STEP 次
                    if (percent < 100) progress.value = percent / PROGRESS_STEP * PROGRESS_STEP
                }
            }
        } finally {
            writer.cancel()
        }
    }

    /**
     * 等待连接（最多 [connectWaitMillis]）。
     *
     * @return 已连接时为 true
     */
    private suspend fun awaitConnected(): Boolean =
        withTimeoutOrNull(connectWaitMillis) { client.connectionState.first { it is ConnectionState.Authenticated } } != null

    /**
     * 上传失败：未完成的附件标记失败，消息标记"发送失败"。
     *
     * @param db 数据库
     * @param message 消息
     * @param files 附件
     * @param errorCode 失败原因
     */
    private suspend fun fail(db: ImDatabase, message: MessageEntity, files: List<MediaFileEntity>, errorCode: String) {
        val current = db.mediaFileDao().forMessage(message.rowId)
        current.filter { it.state != MediaFileEntity.STATE_DONE }.forEach { db.mediaFileDao().setState(it.id, MediaFileEntity.STATE_FAILED, 0) }
        db.messageDao().markSendFailed(message.rowId, errorCode)
        log.info("媒体消息发送失败：msgId={}，原因={}，附件 {} 个", message.msgId, errorCode, files.size)
    }

    /**
     * 上传完成的文件移入媒体缓存（按下载地址命名），移动失败时保留原位置。
     *
     * @param local 本地文件
     * @param url 下载地址
     * @return 文件的新位置
     */
    private fun moveToCache(local: File, url: String): File {
        val target = cache()?.fileFor(url, local.name) ?: return local
        target.parentFile?.mkdirs()
        return if (local.renameTo(target)) target else local
    }

    /**
     * 本地文件不存在（被清理）。
     *
     * @param path 路径
     */
    private class MissingFileException(path: String?) : Exception("文件不存在：$path")

    companion object {
        private const val TAG = "MediaSender"

        /** 未连接时最长等待时间（与 SendQueue 一致） */
        const val CONNECT_WAIT_MILLIS = 30_000L

        /** 语音消息的文件名（协议规范 §7.3 示例） */
        const val VOICE_NAME = "voice.m4a"

        /** 语音的 MIME 类型 */
        const val VOICE_MIME = "audio/mp4"

        /** 不知道类型时的 MIME */
        const val DEFAULT_MIME = "application/octet-stream"

        /** 进度写库的间隔（百分点） */
        private const val PROGRESS_STEP = 10
    }
}

/**
 * 附件是否还没上传完（下载地址为空）：这样的消息不能转发。
 *
 * @return 还没有下载地址时为 true
 */
fun MessageContent.hasPendingUpload(): Boolean = when (this) {
    is MessageContent.Image -> file.url.isEmpty() || thumbnail?.url?.isEmpty() == true
    is MessageContent.Video -> file.url.isEmpty() || thumbnail?.url?.isEmpty() == true
    is MessageContent.Voice -> file.url.isEmpty()
    is MessageContent.File -> file.url.isEmpty()
    else -> false
}

/**
 * 填入附件的下载地址。
 *
 * @param role 附件角色（MediaFileEntity.ROLE_*）
 * @param url 下载地址
 * @return 新内容
 */
internal fun MessageContent.withUrl(role: Int, url: String): MessageContent {
    val thumb = role == MediaFileEntity.ROLE_THUMBNAIL
    return when (this) {
        is MessageContent.Image -> if (thumb) copy(thumbnail = thumbnail?.copy(url = url)) else copy(file = file.copy(url = url))
        is MessageContent.Video -> if (thumb) copy(thumbnail = thumbnail?.copy(url = url)) else copy(file = file.copy(url = url))
        is MessageContent.Voice -> copy(file = file.copy(url = url))
        is MessageContent.File -> copy(file = file.copy(url = url))
        else -> this
    }
}

/**
 * 附件的 MIME 类型。
 *
 * @param role 附件角色
 * @return MIME 类型
 */
internal fun MessageContent.mimeOf(role: Int): String {
    val thumb = role == MediaFileEntity.ROLE_THUMBNAIL
    return when (this) {
        is MessageContent.Image -> if (thumb) thumbnail?.mime ?: "image/jpeg" else file.mime
        is MessageContent.Video -> if (thumb) thumbnail?.mime ?: "image/jpeg" else file.mime
        is MessageContent.Voice -> file.mime
        is MessageContent.File -> file.mime
        else -> "application/octet-stream"
    }
}
