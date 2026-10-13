package cn.android666.im.it

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.common.UuidIdGenerator
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.media.ImageCompressor
import cn.android666.im.core.network.HttpClients
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.ServerConfig
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.smack.SmackXmppClient
import cn.android666.im.data.file.FileDownloader
import cn.android666.im.data.file.FileUploader
import cn.android666.im.data.file.MediaCache
import cn.android666.im.data.message.MediaSender
import cn.android666.im.data.message.MessageRepository
import cn.android666.im.data.message.SendQueue
import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 媒体消息端到端测试（开发计划 5.4、5.5）：App 的发送流程（MediaSender：处理文件 → FileUploader 上传 → 发送队列发消息）
 * 经真实 Openfire 转发；接收方按消息中的地址下载附件。
 * - 图片：先传缩略图再传主图，尺寸符合协议规范 §7.2；
 * - 语音：时长、类型符合 §7.3，下载内容与录音文件一致。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*MediaMessageIT*'`
 *
 * 使用测试账号 10005（发送）、10006（接收）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaMessageIT {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.IO
    }

    private val clients = mutableListOf<SmackXmppClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var holder: UserDatabaseHolder

    private val jidA = "${ItServer.user(5)}@${ItServer.domain}"
    private val jidB = "${ItServer.user(6)}@${ItServer.domain}"

    /** App 中的 HTTP 客户端：企业根证书 + 开发域名解析到本机 */
    private val http by lazy { HttpClients.create(ItServer.sslContext, ItServer.trustManager, ItServer.domain, ItServer.HOST) }

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
        val context = ApplicationProvider.getApplicationContext<Context>()
        holder = UserDatabaseHolder { Room.inMemoryDatabaseBuilder(context, ImDatabase::class.java).build() }
        holder.open(ItServer.user(5))
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        clients.forEach { runCatching { it.logout() } }
        if (::holder.isInitialized) holder.close()
    }

    /**
     * 登录并发送初始 presence。
     *
     * @param index 测试账号序号
     * @return 客户端
     */
    private suspend fun login(index: Int): SmackXmppClient = SmackXmppClient(
        serverConfig = ServerConfig(domain = ItServer.domain, host = ItServer.HOST, port = ItServer.PORT),
        trustManager = ItServer.trustManager,
        dispatchers = dispatchers,
        clock = SystemTimeClock,
        logStanzas = { false },
    ).also {
        clients += it
        it.login(Credential(ItServer.user(index), ItServer.testUserPassword))
        it.sendPresence()
    }

    /**
     * 读取图片尺寸。
     *
     * @param file 图片
     * @return 宽 × 高
     */
    private fun size(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth to options.outHeight
    }

    /**
     * App 的媒体消息发送组件（10005 的内存数据库）。
     *
     * @param a 发送方客户端
     * @param cacheDir 媒体缓存目录
     * @return MediaSender
     */
    private suspend fun mediaSender(a: SmackXmppClient, cacheDir: File): MediaSender {
        val max = a.discoverCapabilities().maxUploadFileSize
        val queue = SendQueue(a, holder, scope, SystemTimeClock)
        val repository = MessageRepository(holder, queue, ServerClock(SystemTimeClock), UuidIdGenerator()) { jidA }
        return MediaSender(
            client = a,
            databases = holder,
            repository = repository,
            uploader = FileUploader(a, http, maxFileSize = { max }),
            images = ImageCompressor(),
            workDir = { File(cacheDir, "out") },
            cache = { MediaCache(cacheDir) },
            scope = scope,
            io = Dispatchers.IO,
        )
    }

    @Test
    fun sendImage_receivedAndDownloadable(): Unit = runBlocking {
        val a = login(5)
        val b = login(6)
        val cacheDir = temp.newFolder("cache")
        val sender = mediaSender(a, cacheDir)
        val source = temp.newFile("photo.png")
        source.outputStream().use { createBitmap(2400, 1600).apply { eraseColor(Color.GREEN) }.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT) {
                b.incomingMessages.map { MessageCodec.parse(it) }.first { it.message.content is MessageContent.Image }
            }
        }
        val saved = sender.sendImage(jidB, ConversationEntity.TYPE_SINGLE, source, "photo.png", original = false)

        val message = received.await().message
        assertThat(message.msgId).isEqualTo(saved.msgId)
        assertThat(message.body).isEqualTo("[图片]")
        val content = message.content as MessageContent.Image
        assertThat(content.original).isFalse()
        assertThat(content.file.name).isEqualTo("photo.jpg")
        assertThat(content.file.mime).isEqualTo("image/jpeg")
        assertThat(content.file.width to content.file.height).isEqualTo(1920 to 1280)
        val thumbnail = content.thumbnail!!
        assertThat(thumbnail.width to thumbnail.height).isEqualTo(320 to 213)

        // 接收方下载：缩略图 ≤ 30 KB，主图尺寸与消息一致
        val downloader = FileDownloader(http)
        val thumbFile = downloader.download(thumbnail.url, File(temp.root, "t.jpg"))
        assertThat(thumbFile.length()).isAtMost(ImageCompressor.THUMB_MAX_BYTES.toLong())
        assertThat(size(thumbFile)).isEqualTo(320 to 213)
        val mainFile = downloader.download(content.file.url, File(temp.root, "m.jpg"))
        assertThat(mainFile.length()).isEqualTo(content.file.size)
        assertThat(size(mainFile)).isEqualTo(1920 to 1280)

        // 发送方：消息变为已发送，主图在媒体缓存中（预览时不再下载）
        withTimeout(TIMEOUT) {
            while (holder.require().messageDao().getByRowId(saved.rowId)!!.status != MessageEntity.STATUS_SENT) delay(50)
        }
        assertThat(MediaCache(cacheDir).get(content.file.url, content.file.name)).isNotNull()
    }

    @Test
    fun sendVoice_receivedWithDuration(): Unit = runBlocking {
        val a = login(5)
        val b = login(6)
        val sender = mediaSender(a, temp.newFolder("cache"))
        // 内容不必是真正的 m4a：服务器只负责存取，这里验证上传、转发、下载与报文字段
        val bytes = ByteArray(4_000) { (it % 97).toByte() }
        val recorded = temp.newFile("rec.m4a").apply { writeBytes(bytes) }

        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT) {
                b.incomingMessages.map { MessageCodec.parse(it) }.first { it.message.content is MessageContent.Voice }
            }
        }
        val saved = sender.sendVoice(jidB, ConversationEntity.TYPE_SINGLE, recorded, 4_200)

        val message = received.await().message
        assertThat(message.msgId).isEqualTo(saved.msgId)
        assertThat(message.body).isEqualTo("[语音]")
        val voice = (message.content as MessageContent.Voice).file
        assertThat(voice.duration).isEqualTo(4_200)
        assertThat(voice.mime).isEqualTo("audio/mp4")
        assertThat(voice.size).isEqualTo(bytes.size.toLong())
        val downloaded = FileDownloader(http).download(voice.url, File(temp.root, "v.m4a"))
        assertThat(downloaded.readBytes()).isEqualTo(bytes)
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
