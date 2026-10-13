package cn.android666.im.data.message

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MediaFileEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.media.ImageProcessor
import cn.android666.im.core.media.PreparedImage
import cn.android666.im.core.media.ProcessedImage
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.file.FileTooLargeException
import cn.android666.im.data.file.MediaCache
import cn.android666.im.data.file.UploadFailedException
import cn.android666.im.data.file.Uploader
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [MediaSender] 测试：Room 内存库 + [FakeXmppClient] + 假的上传与图片处理，虚拟时间。
 */
@RunWith(RobolectricTestRunner::class)
class MediaSenderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val client = FakeXmppClient()

    private var holder: UserDatabaseHolder? = null

    /** 顺序生成的消息 ID：m1、m2…… */
    private val ids = object : IdGenerator {
        private var next = 0
        override fun messageId() = "m${++next}"
        override fun roomId() = "g1"
        override fun callId() = "c1"
        override fun uuid() = "u1"
    }

    /**
     * 假的上传：按文件名返回下载地址；可以让某次上传挂起或失败。
     */
    private class FakeUploader : Uploader {
        /** 上传上限 */
        var limit: Long? = 10_000_000

        /** 已上传的文件名（按顺序） */
        val uploaded = mutableListOf<String>()

        /** 按文件名指定的行为：挂起直到完成，或抛出异常 */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** 下一次（任意文件）上传时抛出的异常 */
        val failures = ArrayDeque<Exception>()

        /** 上传前报告的进度（百分比） */
        var progressBeforeGate = 0

        override fun uploadLimit(): Long? = limit

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

    /** 假的图片处理：主图与缩略图都是小文件，尺寸固定 */
    private class FakeImages : ImageProcessor {
        override fun prepare(source: File, outDir: File, original: Boolean): PreparedImage {
            outDir.mkdirs()
            val main = File(outDir, "img${++count}.jpg").apply { writeBytes(ByteArray(MAIN_SIZE)) }
            val thumb = File(outDir, "img${count}_thumb.jpg").apply { writeBytes(ByteArray(100)) }
            return PreparedImage(
                main = ProcessedImage(main, 1920, 1080, "image/jpeg"),
                thumbnail = ProcessedImage(thumb, 320, 180, "image/jpeg"),
                original = original,
            )
        }

        private var count = 0
    }

    private val uploader = FakeUploader()

    @After
    fun tearDown() {
        holder?.close()
    }

    /**
     * 测试环境。
     *
     * @property sender 媒体消息发送
     * @property repository 消息仓库
     * @property db 数据库
     * @property workDir 待上传目录
     * @property cacheDir 媒体缓存目录
     */
    private class Rig(val sender: MediaSender, val repository: MessageRepository, val db: ImDatabase, val workDir: File, val cacheDir: File)

    /**
     * 创建测试环境。
     *
     * @param login 是否先登录
     * @param before 创建 MediaSender 前对数据库的操作（模拟上次运行留下的数据）
     * @return 测试环境
     */
    private suspend fun TestScope.rig(login: Boolean = true, before: suspend (ImDatabase, File) -> Unit = { _, _ -> }): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        val clock = object : Clock {
            override fun nowMillis() = 1_000_000 + testScheduler.currentTime
            override fun elapsedMillis() = testScheduler.currentTime
        }
        val workDir = temp.newFolder("out")
        val cacheDir = temp.newFolder("cache")
        before(db, workDir)
        if (login) client.login(Credential("10001", "x"))
        val queue = SendQueue(client, newHolder, backgroundScope, clock)
        val repository = MessageRepository(newHolder, queue, ServerClock(clock), ids) { "10001@im.example.com" }
        val sender = MediaSender(
            client, newHolder, repository, uploader, FakeImages(),
            workDir = { workDir }, cache = { MediaCache(cacheDir) }, scope = backgroundScope, io = dispatcher,
        )
        runCurrent()
        return Rig(sender, repository, db, workDir, cacheDir)
    }

    /**
     * 发送一张图片。
     *
     * @param original 是否原图
     * @return 写入的消息
     */
    private suspend fun Rig.sendImage(original: Boolean = false): MessageEntity =
        sender.sendImage(BOB, ConversationEntity.TYPE_SINGLE, temp.newFile(), "照片.png", original)

    /** 读取消息 */
    private suspend fun Rig.message(msgId: String): MessageEntity = db.messageDao().get(BOB, msgId)!!

    /** 读取附件 */
    private suspend fun Rig.media(rowId: Long): List<MediaFileEntity> = db.mediaFileDao().forMessage(rowId)

    @Test
    fun sendImage_uploadsThumbnailFirst_thenSendsWithUrls() = runTest {
        val rig = rig()

        val saved = rig.sendImage()
        assertThat(saved.status).isEqualTo(MessageEntity.STATUS_SENDING)
        assertThat(saved.body).isEqualTo("[图片]")
        val draft = MessageJson.decodeContent(saved.type, saved.contentJson) as MessageContent.Image
        assertThat(draft.file.url).isEmpty()
        assertThat(draft.file.name).isEqualTo("照片.jpg")
        assertThat(draft.file.size).isEqualTo(MAIN_SIZE.toLong())
        runCurrent()

        assertThat(uploader.uploaded).containsExactly("img1_thumb.jpg", "img1.jpg").inOrder()
        val stanza = MessageCodec.parse(client.sentMessages.single()).message
        assertThat(stanza.body).isEqualTo("[图片]")
        val content = stanza.content as MessageContent.Image
        assertThat(content.file.url).isEqualTo("https://im.example.com/upload/img1.jpg")
        assertThat(content.file.width).isEqualTo(1920)
        assertThat(content.thumbnail!!.url).isEqualTo("https://im.example.com/upload/img1_thumb.jpg")
        assertThat(content.original).isFalse()
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
        // 本地记录的内容也带上了下载地址
        assertThat(MessageJson.decodeContent("image", rig.message("m1").contentJson)).isEqualTo(content)
        // 附件已完成，文件移入了媒体缓存
        val media = rig.media(saved.rowId)
        assertThat(media.map { it.state }).containsExactly(MediaFileEntity.STATE_DONE, MediaFileEntity.STATE_DONE)
        val cache = MediaCache(rig.cacheDir)
        assertThat(media.first { it.role == MediaFileEntity.ROLE_ORIGINAL }.localPath)
            .isEqualTo(cache.fileFor(content.file.url, "img1.jpg").path)
        assertThat(cache.get(content.file.url, "img1.jpg")).isNotNull()
        assertThat(rig.workDir.listFiles()).isEmpty()
    }

    /** 上传中的图片不会被发送队列发出；之后发的文本先发出 */
    @Test
    fun uploading_notSentByQueue_textGoesFirst() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        uploader.gates["img1.jpg"] = gate
        uploader.progressBeforeGate = 57

        val image = rig.sendImage()
        rig.repository.sendText(BOB, ConversationEntity.TYPE_SINGLE, "文字")
        runCurrent()
        assertThat(client.sentMessages.map { MessageCodec.parse(it).message.id }).containsExactly("m2")
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_SENDING)
        // 进度按 10% 一档写入
        val original = rig.media(image.rowId).first { it.role == MediaFileEntity.ROLE_ORIGINAL }
        assertThat(original.state).isEqualTo(MediaFileEntity.STATE_UPLOADING)
        assertThat(original.progress).isEqualTo(50)

        gate.complete(Unit)
        runCurrent()
        assertThat(client.sentMessages.map { MessageCodec.parse(it).message.id }).containsExactly("m2", "m1").inOrder()
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    /** 原图上传失败 → 发送失败；重发时只重传原图 */
    @Test
    fun uploadFailed_thenResend_onlyRetriesUnfinished() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        uploader.gates["img1.jpg"] = gate

        val saved = rig.sendImage()
        runCurrent()
        // 缩略图已传完，原图挂起中；让原图失败
        uploader.gates.clear()
        uploader.failures += UploadFailedException("HTTP 500")
        gate.completeExceptionally(UploadFailedException("HTTP 500"))
        runCurrent()
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_FAILED)
        assertThat(rig.message("m1").errorCode).isEqualTo(SendError.UPLOAD_FAILED)
        val media = rig.media(saved.rowId).associateBy { it.role }
        assertThat(media.getValue(MediaFileEntity.ROLE_THUMBNAIL).state).isEqualTo(MediaFileEntity.STATE_DONE)
        assertThat(media.getValue(MediaFileEntity.ROLE_ORIGINAL).state).isEqualTo(MediaFileEntity.STATE_FAILED)
        assertThat(client.sentMessages).isEmpty()

        uploader.failures.clear()
        uploader.uploaded.clear()
        assertThat(rig.sender.resend(saved.rowId)).isTrue()
        runCurrent()
        assertThat(uploader.uploaded).containsExactly("img1.jpg")
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
        val content = MessageCodec.parse(client.sentMessages.single()).message.content as MessageContent.Image
        assertThat(content.thumbnail!!.url).endsWith("img1_thumb.jpg")
        assertThat(content.file.url).endsWith("img1.jpg")
    }

    /** 附件都已上传、是消息本身没发出去：重发交给发送队列 */
    @Test
    fun resend_uploadsDone_resendsMessageOnly() = runTest {
        val rig = rig()
        val saved = rig.sendImage()
        runCurrent()
        // 模拟消息本身发送超时失败
        rig.db.openHelper.writableDatabase.execSQL("UPDATE message SET status = 4, errorCode = 'timeout' WHERE rowId = ${saved.rowId}")
        uploader.uploaded.clear()
        client.sentMessages.clear()

        assertThat(rig.sender.resend(saved.rowId)).isTrue()
        runCurrent()
        assertThat(uploader.uploaded).isEmpty()
        assertThat(client.sentMessages).hasSize(1)
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    /** 超过上限：不写入消息，删除处理后的文件 */
    @Test
    fun tooLarge_throwsWithoutMessage() = runTest {
        val rig = rig()
        uploader.limit = MAIN_SIZE - 1L

        val error = runCatching { rig.sendImage() }.exceptionOrNull()
        assertThat(error).isInstanceOf(FileTooLargeException::class.java)
        assertThat(rig.db.messageDao().pending()).isEmpty()
        assertThat(rig.db.conversationDao().get(BOB)).isNull()
        assertThat(rig.workDir.listFiles()).isEmpty()
    }

    /** 原图标记随消息发出 */
    @Test
    fun original_flagSent() = runTest {
        val rig = rig()
        rig.sendImage(original = true)
        runCurrent()
        val content = MessageCodec.parse(client.sentMessages.single()).message.content as MessageContent.Image
        assertThat(content.original).isTrue()
    }

    /** 未连接：等待 30 秒仍未连上 → 失败 */
    @Test
    fun notConnected_failsAfterWait() = runTest {
        val rig = rig(login = false)
        rig.sendImage()
        advanceTimeBy(MediaSender.CONNECT_WAIT_MILLIS - 1)
        runCurrent()
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_SENDING)
        advanceTimeBy(2)
        runCurrent()
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_FAILED)
        assertThat(rig.message("m1").errorCode).isEqualTo(SendError.NOT_CONNECTED)
        assertThat(uploader.uploaded).isEmpty()
    }

    /** 等待期间连上了：继续上传并发送 */
    @Test
    fun notConnected_connectsWithinWait_sends() = runTest {
        val rig = rig(login = false)
        rig.sendImage()
        advanceTimeBy(10_000)
        client.login(Credential("10001", "x"))
        runCurrent()
        assertThat(uploader.uploaded).hasSize(2)
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_SENT)
    }

    /** 本地文件被清理：失败，原因为文件不存在 */
    @Test
    fun localFileMissing_fails() = runTest {
        val rig = rig(login = false)
        val saved = rig.sendImage()
        rig.media(saved.rowId).forEach { File(it.localPath!!).delete() }
        client.login(Credential("10001", "x"))
        runCurrent()
        assertThat(rig.message("m1").status).isEqualTo(MessageEntity.STATUS_FAILED)
        assertThat(rig.message("m1").errorCode).isEqualTo(SendError.FILE_MISSING)
    }

    /** 应用被杀时还在上传：重新打开数据库后自动继续上传并发送 */
    @Test
    fun interruptedUpload_resumedOnOpen() = runTest {
        val rig = rig { db, workDir ->
            val main = File(workDir, "old.jpg").apply { writeBytes(ByteArray(10)) }
            val thumb = File(workDir, "old_thumb.jpg").apply { writeBytes(ByteArray(10)) }
            val content = MessageContent.Image(
                cn.android666.im.core.xmpp.extension.msg.FileInfo("", "old.jpg", 10, "image/jpeg", width = 10, height = 10),
                cn.android666.im.core.xmpp.extension.msg.ThumbnailInfo("https://im.example.com/upload/old_thumb.jpg", 10, 10, "image/jpeg"),
            )
            val rowId = db.messageDao().insert(
                MessageEntity(
                    msgId = "old", conversationId = BOB, senderJid = "10001@im.example.com", direction = MessageEntity.DIRECTION_OUT,
                    type = "image", body = "[图片]", contentJson = MessageJson.encodeContent(content),
                    status = MessageEntity.STATUS_SENDING, serverTime = 1,
                ),
            )
            db.mediaFileDao().insertAll(
                listOf(
                    MediaFileEntity(msgRowId = rowId, role = MediaFileEntity.ROLE_ORIGINAL, localPath = main.path, state = MediaFileEntity.STATE_UPLOADING),
                    MediaFileEntity(
                        msgRowId = rowId, role = MediaFileEntity.ROLE_THUMBNAIL, url = "https://im.example.com/upload/old_thumb.jpg",
                        localPath = thumb.path, state = MediaFileEntity.STATE_DONE,
                    ),
                ),
            )
        }
        runCurrent()
        assertThat(uploader.uploaded).containsExactly("old.jpg")
        assertThat(rig.message("old").status).isEqualTo(MessageEntity.STATUS_SENT)
        val content = MessageCodec.parse(client.sentMessages.single()).message.content as MessageContent.Image
        assertThat(content.file.url).isEqualTo("https://im.example.com/upload/old.jpg")
    }

    /** 还没上传完的图片不能转发 */
    @Test
    fun forward_skipsPendingUpload() = runTest {
        val rig = rig(login = false)
        val saved = rig.sendImage()
        assertThat(rig.repository.forward(CAROL, ConversationEntity.TYPE_SINGLE, listOf(saved.rowId), null)).isEqualTo(0)
    }

    /** 删除上传中的消息：附件记录一并删除，上传结束后不会发送 */
    @Test
    fun deleteWhileUploading_notSent() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        uploader.gates["img1.jpg"] = gate
        val saved = rig.sendImage()
        runCurrent()
        rig.repository.deleteLocal(listOf(saved.rowId))
        assertThat(rig.media(saved.rowId)).isEmpty()
        gate.complete(Unit)
        runCurrent()
        assertThat(client.sentMessages).isEmpty()
    }

    private companion object {
        const val BOB = "10002@im.example.com"
        const val CAROL = "10003@im.example.com"

        /** 假主图的大小 */
        const val MAIN_SIZE = 2000
    }
}
