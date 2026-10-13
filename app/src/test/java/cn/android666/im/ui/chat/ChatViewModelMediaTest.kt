package cn.android666.im.ui.chat

import android.content.Context
import android.net.Uri
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.common.UuidIdGenerator
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.MediaFileEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.media.ImportedFile
import cn.android666.im.core.media.MediaImporter
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.extension.msg.FileInfo
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.data.file.Downloader
import cn.android666.im.data.file.MediaCache
import cn.android666.im.data.file.UploadFailedException
import cn.android666.im.data.message.MediaSender
import cn.android666.im.data.message.FileDownloads
import cn.android666.im.data.message.MessageJson
import cn.android666.im.data.message.MessageRepository
import cn.android666.im.data.message.MessageState
import cn.android666.im.data.message.ReadMarkers
import cn.android666.im.data.message.Retractions
import cn.android666.im.data.message.SendError
import cn.android666.im.data.message.SendQueue
import cn.android666.im.data.message.VoicePlayback
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeImageProcessor
import cn.android666.im.testing.FakeUploader
import cn.android666.im.testing.FakeVoicePlayer
import cn.android666.im.testing.FakeVoiceRecorder
import cn.android666.im.testing.FakeXmppClient
import cn.android666.im.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** [ChatViewModel] 的图片发送与媒体消息重发（开发计划 5.4） */
@RunWith(RobolectricTestRunner::class)
class ChatViewModelMediaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val client = FakeXmppClient()
    private val uploader = FakeUploader()
    private val images = FakeImageProcessor()
    private lateinit var holder: UserDatabaseHolder
    private lateinit var db: ImDatabase

    /** 假的导入：按 Uri 最后一段生成本地副本；"bad" 时失败 */
    private inner class FakeImporter : MediaImporter {
        /** 生成的副本 */
        val copies = mutableListOf<File>()

        override suspend fun import(uri: Uri): ImportedFile {
            if (uri.lastPathSegment == "bad") throw IOException("无法读取")
            val file = temp.newFile().apply { writeBytes(byteArrayOf(1)) }
            copies += file
            return ImportedFile(file, "${uri.lastPathSegment}.png", "image/png")
        }
    }

    private val importer = FakeImporter()

    @Before
    fun setUp() {
        holder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(mainDispatcherRule.testDispatcher)
                .allowMainThreadQueries()
                .build()
        }
        db = holder.open("10001")
    }

    @After
    fun tearDown() = holder.close()

    private val recorder = FakeVoiceRecorder()

    /** 下载的文件目录 */
    private val filesRoot: File by lazy { temp.newFolder("files") }
    private val player = FakeVoicePlayer()

    /** 被测对象 */
    private fun TestScope.newVm(): ChatViewModel {
        val clock = FakeClock(startMillis = 10_000_000)
        val queue = SendQueue(client, holder, backgroundScope, clock)
        val messages = MessageRepository(holder, queue, ServerClock(clock), UuidIdGenerator()) { "10001@im.example.com" }
        val conversations = ConversationRepository(holder)
        val readMarkers = ReadMarkers(client, messages, conversations, UuidIdGenerator())
        val retractions = Retractions(client, messages, ServerClock(clock), UuidIdGenerator(), { "10001@im.example.com" }, selfName = { null })
        val workDir = temp.newFolder()
        val cacheDir = temp.newFolder()
        val media = MediaSender(
            client, holder, messages, uploader, images, workDir = { workDir }, cache = { MediaCache(cacheDir) },
            scope = backgroundScope, io = mainDispatcherRule.testDispatcher,
        )
        val playback = VoicePlayback(
            player, FakeVoiceDownloader(), { MediaCache(cacheDir) }, holder, { false }, backgroundScope, mainDispatcherRule.testDispatcher,
        )
        val vmClock = object : Clock {
            override fun nowMillis() = testScheduler.currentTime
            override fun elapsedMillis() = testScheduler.currentTime
        }
        val downloads = FileDownloads(holder, FakeVoiceDownloader(), { filesRoot }, backgroundScope, mainDispatcherRule.testDispatcher)
        return ChatViewModel(
            BOB, ConversationKind.SINGLE, messages, conversations, readMarkers, retractions, OrgRepository(client, holder, clock),
            media = media, importer = importer, voiceRecorder = recorder, voiceDir = temp.newFolder("voice"),
            voicePlayback = playback, clock = vmClock, downloads = downloads,
        )
    }

    /** 未登录：图片按顺序写入、显示上传中（本地缩略图）；导入的副本发送后删除 */
    @Test
    fun sendImages_appearUploading_tempDeleted() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()

        vm.sendImages(listOf(Uri.parse("content://x/a"), Uri.parse("content://x/b")), original = true)
        runCurrent()

        val messages = vm.items.asSnapshot().filterIsInstance<ChatListItem.Message>().map { it.message }
        assertThat(messages).hasSize(2)
        val newest = messages.first()
        assertThat(newest.state).isEqualTo(MessageState.SENDING)
        val content = newest.content as MessageContent.Image
        assertThat(content.original).isTrue()
        assertThat(content.file.name).isEqualTo("b.jpg")
        assertThat(newest.media!!.uploading).isTrue()
        assertThat(File(newest.media!!.thumbnailPath!!).exists()).isTrue()
        assertThat(importer.copies.none { it.exists() }).isTrue()
        assertThat(images.sources).hasSize(2)
    }

    /** 已登录：上传完成后发出 */
    @Test
    fun sendImages_loggedIn_sent() = runTest(mainDispatcherRule.testDispatcher) {
        client.login(Credential("10001", "x"))
        val vm = newVm()

        vm.sendImages(listOf(Uri.parse("content://x/a")), original = false)
        runCurrent()

        assertThat(uploader.uploaded).hasSize(2)
        assertThat(client.sentMessages).hasSize(1)
        val message = (vm.items.asSnapshot().first() as ChatListItem.Message).message
        assertThat(message.state).isEqualTo(MessageState.SENT)
        assertThat(message.media!!.uploading).isFalse()
    }

    /** 某张导入失败：提示，其余照常发送 */
    @Test
    fun sendImages_oneUnreadable_toastAndContinue() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()

        vm.events.test {
            vm.sendImages(listOf(Uri.parse("content://x/bad"), Uri.parse("content://x/b")), original = false)
            runCurrent()
            assertThat(awaitItem()).isEqualTo(ChatEvent.Toast(R.string.chat_image_invalid))
            expectNoEvents()
        }
        assertThat(db.messageDao().last(BOB)!!.type).isEqualTo("image")
    }

    /** 超过上传上限：提示上限，不写入消息 */
    @Test
    fun sendImages_tooLarge_event() = runTest(mainDispatcherRule.testDispatcher) {
        uploader.limit = 1_000
        val vm = newVm()

        vm.events.test {
            vm.sendImages(listOf(Uri.parse("content://x/a")), original = true)
            runCurrent()
            assertThat(awaitItem()).isEqualTo(ChatEvent.FileTooLarge(1_000))
        }
        assertThat(db.messageDao().last(BOB)).isNull()
    }

    /** 拍照：发送后删除照片文件 */
    @Test
    fun sendPhoto_deletesFile() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()
        val photo = temp.newFile("photo.jpg").apply { writeBytes(byteArrayOf(1)) }

        vm.sendPhoto(photo, "IMG_1.jpg")
        runCurrent()

        assertThat(photo.exists()).isFalse()
        val content = (vm.items.asSnapshot().first() as ChatListItem.Message).message.content as MessageContent.Image
        assertThat(content.original).isFalse()
        assertThat(content.file.name).isEqualTo("IMG_1.jpg")
    }

    /** 上传失败的图片：重发时重新上传 */
    @Test
    fun resend_failedUpload_reuploads() = runTest(mainDispatcherRule.testDispatcher) {
        client.login(Credential("10001", "x"))
        uploader.failures += UploadFailedException("HTTP 500")
        val vm = newVm()
        vm.sendImages(listOf(Uri.parse("content://x/a")), original = false)
        runCurrent()
        val failed = db.messageDao().last(BOB)!!
        assertThat(failed.status).isEqualTo(MessageEntity.STATUS_FAILED)
        assertThat(failed.errorCode).isEqualTo(SendError.UPLOAD_FAILED)

        vm.resend(failed.rowId)
        runCurrent()

        assertThat(db.messageDao().last(BOB)!!.status).isEqualTo(MessageEntity.STATUS_SENT)
        assertThat(client.sentMessages).hasSize(1)
    }

    /** 按住说话 3 秒后松开：发送语音 */
    @Test
    fun voice_holdAndRelease_sent() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()

        vm.onVoicePress()
        assertThat(vm.recording.value).isNotNull()
        advanceTimeBy(3_000)
        vm.onVoiceRelease()
        runCurrent()

        assertThat(vm.recording.value).isNull()
        val message = db.messageDao().last(BOB)!!
        assertThat(message.type).isEqualTo("voice")
        val content = MessageJson.decodeContent(message.type, message.contentJson) as MessageContent.Voice
        assertThat(content.file.duration).isEqualTo(3_000)
    }

    /** 不足 1 秒：提示太短，不发送 */
    @Test
    fun voice_tooShort_toast() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()

        vm.events.test {
            vm.onVoicePress()
            advanceTimeBy(500)
            vm.onVoiceRelease()
            runCurrent()
            assertThat(awaitItem()).isEqualTo(ChatEvent.Toast(R.string.chat_voice_too_short))
        }
        assertThat(db.messageDao().last(BOB)).isNull()
    }

    /** 上滑取消：不发送也不提示 */
    @Test
    fun voice_slideCancel() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()

        vm.events.test {
            vm.onVoicePress()
            advanceTimeBy(2_000)
            vm.onVoiceMove(cancelZone = true)
            assertThat(vm.recording.value!!.cancelZone).isTrue()
            vm.onVoiceRelease()
            runCurrent()
            expectNoEvents()
        }
        assertThat(db.messageDao().last(BOB)).isNull()
    }

    /** 离开页面：取消录音、停止播放 */
    @Test
    fun hidden_abortsRecordingAndStopsPlayback() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()
        vm.onVoicePress()
        advanceTimeBy(2_000)

        vm.onHidden()
        runCurrent()
        assertThat(vm.recording.value).isNull()
        assertThat(recorder.calls).contains("cancel")
        assertThat(db.messageDao().last(BOB)).isNull()
    }

    /** 点击语音播放，再点停止；开始录音时停止播放 */
    @Test
    fun playVoice_toggleAndStopOnRecord() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()
        val content = MessageContent.Voice(FileInfo("https://h/a.m4a", "voice.m4a", 1, "audio/mp4", duration = 2_000))
        val row = db.messageDao().insert(
            MessageEntity(
                msgId = "v", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "voice",
                body = "[语音]", contentJson = MessageJson.encodeContent(content), status = MessageEntity.STATUS_RECEIVED, serverTime = 1,
            ),
        )

        vm.playVoice(row)
        runCurrent()
        assertThat(vm.playingVoice.value).isEqualTo(row)
        assertThat(player.current).isNotNull()

        vm.onVoicePress()
        assertThat(vm.playingVoice.value).isNull()
        assertThat(player.current).isNull()
        vm.onVoiceAbort()
    }

    /** 选择文件发送：原名与类型来自导入结果；副本被移走 */
    @Test
    fun sendFiles_sent() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()

        vm.sendFiles(listOf(Uri.parse("content://x/report")))
        runCurrent()

        val message = db.messageDao().last(BOB)!!
        assertThat(message.type).isEqualTo("file")
        val content = MessageJson.decodeContent(message.type, message.contentJson) as MessageContent.File
        assertThat(content.file.name).isEqualTo("report.png")
        assertThat(content.file.mime).isEqualTo("image/png")
        assertThat(importer.copies.none { it.exists() }).isTrue()
    }

    /** 文件超过上限：提示，不写入 */
    @Test
    fun sendFiles_tooLarge() = runTest(mainDispatcherRule.testDispatcher) {
        uploader.limit = 0
        val vm = newVm()
        vm.events.test {
            vm.sendFiles(listOf(Uri.parse("content://x/report")))
            runCurrent()
            assertThat(awaitItem()).isEqualTo(ChatEvent.FileTooLarge(0))
        }
        assertThat(db.messageDao().last(BOB)).isNull()
    }

    /** 收到的文件：未下载时点击下载，下载完成后点击打开 */
    @Test
    fun openFile_downloadThenOpen() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()
        val content = MessageContent.File(FileInfo("https://h/r.pdf", "r.pdf", 2, "application/pdf"))
        val row = db.messageDao().insert(
            MessageEntity(
                msgId = "f", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "file",
                body = "[文件] r.pdf", contentJson = MessageJson.encodeContent(content), status = MessageEntity.STATUS_RECEIVED, serverTime = 1,
            ),
        )
        vm.openFile(vm.items.asSnapshot().filterIsInstance<ChatListItem.Message>().single().message)
        runCurrent()
        val done = db.mediaFileDao().get(row, MediaFileEntity.ROLE_ORIGINAL)!!
        assertThat(done.state).isEqualTo(MediaFileEntity.STATE_DONE)

        vm.events.test {
            vm.openFile(vm.items.asSnapshot().filterIsInstance<ChatListItem.Message>().single().message)
            runCurrent()
            assertThat(awaitItem()).isEqualTo(ChatEvent.OpenFile(done.localPath!!, "application/pdf"))
        }
    }

    /** 已过期：提示 */
    @Test
    fun openFile_expired_toast() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newVm()
        val content = MessageContent.File(FileInfo("https://h/r.pdf", "r.pdf", 2, "application/pdf"))
        val row = db.messageDao().insert(
            MessageEntity(
                msgId = "f", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "file",
                body = "[文件] r.pdf", contentJson = MessageJson.encodeContent(content), status = MessageEntity.STATUS_RECEIVED, serverTime = 1,
            ),
        )
        db.mediaFileDao().upsert(
            MediaFileEntity(
                msgRowId = row, role = MediaFileEntity.ROLE_ORIGINAL,
                state = MediaFileEntity.STATE_EXPIRED,
            ),
        )
        vm.events.test {
            vm.openFile(vm.items.asSnapshot().filterIsInstance<ChatListItem.Message>().single().message)
            runCurrent()
            assertThat(awaitItem()).isEqualTo(ChatEvent.Toast(R.string.chat_file_expired))
        }
    }

    /** 假的下载：写入目标文件 */
    private class FakeVoiceDownloader : Downloader {
        override suspend fun download(url: String, target: File, sha256: String?, onProgress: (Long, Long) -> Unit): File {
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(1))
            return target
        }
    }

    private companion object {
        const val BOB = "10002@im.example.com"
    }
}
