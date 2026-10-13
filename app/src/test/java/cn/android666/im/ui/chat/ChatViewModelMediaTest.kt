package cn.android666.im.ui.chat

import android.content.Context
import android.net.Uri
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.common.UuidIdGenerator
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.media.ImportedFile
import cn.android666.im.core.media.MediaImporter
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.data.file.MediaCache
import cn.android666.im.data.message.MediaSender
import cn.android666.im.data.message.MessageRepository
import cn.android666.im.data.message.MessageState
import cn.android666.im.data.message.ReadMarkers
import cn.android666.im.data.message.Retractions
import cn.android666.im.data.message.SendError
import cn.android666.im.data.message.SendQueue
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeImageProcessor
import cn.android666.im.testing.FakeUploader
import cn.android666.im.testing.FakeXmppClient
import cn.android666.im.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
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
        return ChatViewModel(
            BOB, ConversationKind.SINGLE, messages, conversations, readMarkers, retractions, OrgRepository(client, holder, clock),
            media = media, importer = importer,
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
        uploader.failures += cn.android666.im.data.file.UploadFailedException("HTTP 500")
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

    private companion object {
        const val BOB = "10002@im.example.com"
    }
}
