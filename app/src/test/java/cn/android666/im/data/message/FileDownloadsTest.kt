package cn.android666.im.data.message

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.MediaFileEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.xmpp.extension.msg.FileInfo
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.data.file.ChecksumMismatchException
import cn.android666.im.data.file.Downloader
import cn.android666.im.data.file.FileExpiredException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** [FileDownloads] 测试：下载与进度、暂停与续传、过期、校验失败、重启后暂停 */
@RunWith(RobolectricTestRunner::class)
class FileDownloadsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var holder: UserDatabaseHolder? = null

    /** 假的下载：报告进度后在闸门处等待；可按队列失败 */
    private class FakeDownloader : Downloader {
        var gate: CompletableDeferred<Unit>? = null
        val errors = ArrayDeque<IOException>()
        var calls = 0

        override suspend fun download(url: String, target: File, sha256: String?, onProgress: (Long, Long) -> Unit): File {
            calls++
            onProgress(37, 100)
            gate?.await()
            errors.removeFirstOrNull()?.let { throw it }
            target.parentFile?.mkdirs()
            target.writeText("内容")
            onProgress(100, 100)
            return target
        }
    }

    private val downloader = FakeDownloader()

    @After
    fun tearDown() {
        holder?.close()
    }

    /**
     * 测试环境。
     *
     * @property downloads 被测对象
     * @property db 数据库
     * @property root 文件目录
     */
    private class Rig(val downloads: FileDownloads, val db: ImDatabase, val root: File)

    /**
     * 创建测试环境。
     *
     * @param before 创建前对数据库的操作
     * @return 测试环境
     */
    private suspend fun TestScope.rig(before: suspend (ImDatabase) -> Unit = {}): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        before(db)
        val root = temp.newFolder("files")
        val downloads = FileDownloads(newHolder, downloader, { root }, backgroundScope, dispatcher)
        runCurrent()
        return Rig(downloads, db, root)
    }

    /** 插入一条收到的文件消息 */
    private suspend fun insertFile(db: ImDatabase): Long {
        val content = MessageContent.File(FileInfo(URL, "季度报告.pdf", 1_000, "application/pdf", sha256 = "abc"))
        return db.messageDao().insert(
            MessageEntity(
                msgId = "f", conversationId = BOB, senderJid = BOB, direction = MessageEntity.DIRECTION_IN, type = "file",
                body = "[文件] 季度报告.pdf", contentJson = MessageJson.encodeContent(content), status = MessageEntity.STATUS_RECEIVED, serverTime = 1,
            ),
        )
    }

    /** 读取附件记录 */
    private suspend fun Rig.record(rowId: Long) = db.mediaFileDao().get(rowId, MediaFileEntity.ROLE_ORIGINAL)

    @Test
    fun download_progressThenDone() = runTest {
        val rig = rig()
        val row = insertFile(rig.db)
        val gate = CompletableDeferred<Unit>()
        downloader.gate = gate

        rig.downloads.toggle(row)
        runCurrent()
        assertThat(rig.downloads.isDownloading(row)).isTrue()
        val downloading = rig.record(row)!!
        assertThat(downloading.state).isEqualTo(MediaFileEntity.STATE_DOWNLOADING)
        assertThat(downloading.progress).isEqualTo(35)
        assertThat(downloading.url).isEqualTo(URL)
        assertThat(downloading.localPath).isEqualTo(FileDownloads.targetFor(rig.root, URL, "季度报告.pdf").path)

        gate.complete(Unit)
        runCurrent()
        val done = rig.record(row)!!
        assertThat(done.state).isEqualTo(MediaFileEntity.STATE_DONE)
        assertThat(done.progress).isEqualTo(100)
        assertThat(File(done.localPath!!).readText()).isEqualTo("内容")
        assertThat(File(done.localPath!!).name).isEqualTo("季度报告.pdf")
        assertThat(rig.downloads.isDownloading(row)).isFalse()
    }

    /** 下载中再次点击：暂停（保留进度）；再点击：继续 */
    @Test
    fun toggle_pauseAndResume() = runTest {
        val rig = rig()
        val row = insertFile(rig.db)
        downloader.gate = CompletableDeferred()

        rig.downloads.toggle(row)
        runCurrent()
        rig.downloads.toggle(row)
        runCurrent()
        val paused = rig.record(row)!!
        assertThat(paused.state).isEqualTo(MediaFileEntity.STATE_NOT_DOWNLOADED)
        assertThat(paused.progress).isEqualTo(35)

        downloader.gate = null
        rig.downloads.toggle(row)
        runCurrent()
        assertThat(rig.record(row)!!.state).isEqualTo(MediaFileEntity.STATE_DONE)
        assertThat(downloader.calls).isEqualTo(2)
    }

    @Test
    fun notFound_expired() = runTest {
        val rig = rig()
        val row = insertFile(rig.db)
        downloader.errors += FileExpiredException(URL)

        rig.downloads.toggle(row)
        runCurrent()
        assertThat(rig.record(row)!!.state).isEqualTo(MediaFileEntity.STATE_EXPIRED)
    }

    @Test
    fun checksumMismatch_failed() = runTest {
        val rig = rig()
        val row = insertFile(rig.db)
        downloader.errors += ChecksumMismatchException("abc", "def")

        rig.downloads.toggle(row)
        runCurrent()
        assertThat(rig.record(row)!!.state).isEqualTo(MediaFileEntity.STATE_FAILED)
    }

    /** 网络错误：失败，再点击重试成功 */
    @Test
    fun networkError_failedThenRetry() = runTest {
        val rig = rig()
        val row = insertFile(rig.db)
        downloader.errors += IOException("断网")

        rig.downloads.toggle(row)
        runCurrent()
        assertThat(rig.record(row)!!.state).isEqualTo(MediaFileEntity.STATE_FAILED)

        rig.downloads.toggle(row)
        runCurrent()
        assertThat(rig.record(row)!!.state).isEqualTo(MediaFileEntity.STATE_DONE)
    }

    /** 应用被杀时还在下载：重新打开后改为已暂停，不自动继续 */
    @Test
    fun interrupted_pausedOnOpen() = runTest {
        var row = 0L
        val rig = rig { db ->
            row = insertFile(db)
            db.mediaFileDao().upsert(
                MediaFileEntity(msgRowId = row, role = MediaFileEntity.ROLE_ORIGINAL, url = URL, state = MediaFileEntity.STATE_DOWNLOADING, progress = 60),
            )
        }
        val record = rig.record(row)!!
        assertThat(record.state).isEqualTo(MediaFileEntity.STATE_NOT_DOWNLOADED)
        assertThat(record.progress).isEqualTo(60)
        assertThat(downloader.calls).isEqualTo(0)
    }

    /** 创建后立刻开始下载（初始化中的数据库订阅还没运行）：下载不被取消 */
    @Test
    fun toggleRightAfterCreate_notCanceled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        val row = insertFile(db)
        val downloads = FileDownloads(newHolder, downloader, { temp.newFolder("f") }, backgroundScope, dispatcher)

        downloads.toggle(row)
        runCurrent()
        assertThat(db.mediaFileDao().get(row, MediaFileEntity.ROLE_ORIGINAL)!!.state).isEqualTo(MediaFileEntity.STATE_DONE)
    }

    /** 切换账号：正在进行的下载被取消 */
    @Test
    fun accountSwitch_cancelsDownload() = runTest {
        val rig = rig()
        val row = insertFile(rig.db)
        downloader.gate = CompletableDeferred()
        rig.downloads.toggle(row)
        runCurrent()
        assertThat(rig.downloads.isDownloading(row)).isTrue()

        holder!!.open("10002")
        runCurrent()
        assertThat(rig.downloads.isDownloading(row)).isFalse()
    }

    @Test
    fun targetFor_sameUrlSameDir_safeName() {
        val root = File("/r")
        val a = FileDownloads.targetFor(root, URL, "a/b.pdf")
        assertThat(a.name).isEqualTo("a_b.pdf")
        assertThat(a.parentFile!!.parentFile).isEqualTo(root)
        assertThat(FileDownloads.targetFor(root, URL, "c.pdf").parentFile).isEqualTo(a.parentFile)
        assertThat(FileDownloads.targetFor(root, "$URL-2", "c.pdf").parentFile).isNotEqualTo(a.parentFile)
    }

    private companion object {
        const val BOB = "10002@im.example.com"
        const val URL = "https://im.example.com/upload/abc/report.pdf"
    }
}
