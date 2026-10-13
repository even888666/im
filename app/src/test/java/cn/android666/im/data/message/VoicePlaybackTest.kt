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
import cn.android666.im.data.file.Downloader
import cn.android666.im.data.file.FileExpiredException
import cn.android666.im.data.file.MediaCache
import cn.android666.im.testing.FakeVoicePlayer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
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

/** [VoicePlayback] 测试：本地 / 缓存 / 下载、已听标记、自动播放下一条、切换与停止、失败 */
@RunWith(RobolectricTestRunner::class)
class VoicePlaybackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val player = FakeVoicePlayer()
    private var holder: UserDatabaseHolder? = null

    /** 假的下载：写入目标文件；可按队列失败 */
    private class FakeDownloader : Downloader {
        val errors = ArrayDeque<IOException>()
        val urls = mutableListOf<String>()

        override suspend fun download(url: String, target: File, sha256: String?, onProgress: (Long, Long) -> Unit): File {
            urls += url
            errors.removeFirstOrNull()?.let { throw it }
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(1))
            return target
        }
    }

    private val downloader = FakeDownloader()

    /** 听筒设置 */
    private var earpiece = false

    @After
    fun tearDown() {
        holder?.close()
    }

    /**
     * 测试环境。
     *
     * @property playback 被测对象
     * @property db 数据库
     * @property cache 媒体缓存
     */
    private class Rig(val playback: VoicePlayback, val db: ImDatabase, val cache: MediaCache)

    /** 创建测试环境 */
    private fun TestScope.rig(): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val newHolder = UserDatabaseHolder {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
        }
        holder = newHolder
        val db = newHolder.open("10001")
        val cache = MediaCache(temp.newFolder("cache"))
        val playback = VoicePlayback(player, downloader, { cache }, newHolder, { earpiece }, backgroundScope, dispatcher)
        return Rig(playback, db, cache)
    }

    /**
     * 插入一条语音消息。
     *
     * @param id 消息 ID
     * @param time 时间
     * @param direction 方向
     * @param played 是否已听
     * @return rowId
     */
    private suspend fun Rig.voice(id: String, time: Long, direction: Int = MessageEntity.DIRECTION_IN, played: Boolean = false): Long {
        val content = MessageContent.Voice(FileInfo("https://h/$id.m4a", "voice.m4a", 10, "audio/mp4", duration = 3_000))
        return db.messageDao().insert(
            MessageEntity(
                msgId = id, conversationId = BOB, senderJid = BOB, direction = direction, type = "voice", body = "[语音]",
                contentJson = MessageJson.encodeContent(content), status = MessageEntity.STATUS_RECEIVED, serverTime = time,
                voicePlayed = played,
            ),
        )
    }

    @Test
    fun play_downloadsThenPlays_marksPlayed() = runTest {
        val rig = rig()
        val row = rig.voice("a", 1)

        rig.playback.toggle(row)
        assertThat(rig.playback.playing.value).isEqualTo(row)
        runCurrent()

        assertThat(downloader.urls).containsExactly("https://h/a.m4a")
        assertThat(player.current!!.first).isEqualTo(rig.cache.fileFor("https://h/a.m4a", "voice.m4a"))
        assertThat(player.current!!.second).isFalse()
        assertThat(rig.db.messageDao().getByRowId(row)!!.voicePlayed).isTrue()

        player.complete()
        runCurrent()
        assertThat(rig.playback.playing.value).isNull()
    }

    /** 第二次播放使用缓存；听筒设置生效 */
    @Test
    fun cached_noDownload_earpiece() = runTest {
        val rig = rig()
        val row = rig.voice("a", 1, played = true)
        rig.cache.fileFor("https://h/a.m4a", "voice.m4a").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        earpiece = true

        rig.playback.toggle(row)
        runCurrent()
        assertThat(downloader.urls).isEmpty()
        assertThat(player.current!!.second).isTrue()
    }

    /** 本人发出的语音：用附件记录中的本地文件 */
    @Test
    fun ownVoice_localFile() = runTest {
        val rig = rig()
        val row = rig.voice("mine", 1, direction = MessageEntity.DIRECTION_OUT)
        val local = temp.newFile("mine.m4a")
        rig.db.mediaFileDao().insertAll(
            listOf(MediaFileEntity(msgRowId = row, role = MediaFileEntity.ROLE_ORIGINAL, localPath = local.path, state = MediaFileEntity.STATE_DONE)),
        )

        rig.playback.toggle(row)
        runCurrent()
        assertThat(player.current!!.first).isEqualTo(local)
        assertThat(downloader.urls).isEmpty()
    }

    /** 再次点击正在播放的语音：停止 */
    @Test
    fun toggleSame_stops() = runTest {
        val rig = rig()
        val row = rig.voice("a", 1)
        rig.playback.toggle(row)
        runCurrent()

        rig.playback.toggle(row)
        assertThat(rig.playback.playing.value).isNull()
        assertThat(player.current).isNull()
    }

    /** 点击另一条：切换 */
    @Test
    fun toggleOther_switches() = runTest {
        val rig = rig()
        val a = rig.voice("a", 1)
        val b = rig.voice("b", 2)
        rig.playback.toggle(a)
        runCurrent()

        rig.playback.toggle(b)
        runCurrent()
        assertThat(rig.playback.playing.value).isEqualTo(b)
        assertThat(player.played.map { it.name }).hasSize(2)
    }

    /** 播放完一条未听语音后自动播放下一条未听的（跳过已听的和本人发出的） */
    @Test
    fun autoPlayNextUnplayed() = runTest {
        val rig = rig()
        val a = rig.voice("a", 1)
        rig.voice("b", 2, played = true)
        rig.voice("c", 3, direction = MessageEntity.DIRECTION_OUT)
        val d = rig.voice("d", 4)

        rig.playback.toggle(a)
        runCurrent()
        player.complete()
        runCurrent()

        assertThat(rig.playback.playing.value).isEqualTo(d)
        assertThat(downloader.urls).containsExactly("https://h/a.m4a", "https://h/d.m4a").inOrder()
        player.complete()
        runCurrent()
        assertThat(rig.playback.playing.value).isNull()
    }

    /** 重听已听过的语音：播放完不自动播放下一条 */
    @Test
    fun playedVoice_noAutoPlay() = runTest {
        val rig = rig()
        val a = rig.voice("a", 1, played = true)
        rig.voice("b", 2)
        rig.playback.toggle(a)
        runCurrent()
        player.complete()
        runCurrent()
        assertThat(player.played).hasSize(1)
        assertThat(rig.playback.playing.value).isNull()
    }

    @Test
    fun expired_error() = runTest {
        val rig = rig()
        val row = rig.voice("a", 1)
        downloader.errors += FileExpiredException("https://h/a.m4a")
        val errors = mutableListOf<VoiceError>()
        backgroundScope.launchCollect(rig.playback, errors)
        runCurrent()

        rig.playback.toggle(row)
        runCurrent()
        assertThat(errors).containsExactly(VoiceError.EXPIRED)
        assertThat(rig.playback.playing.value).isNull()
        assertThat(rig.db.messageDao().getByRowId(row)!!.voicePlayed).isFalse()
    }

    @Test
    fun playError_failed() = runTest {
        val rig = rig()
        val row = rig.voice("a", 1)
        player.playError = IOException("格式不支持")
        val errors = mutableListOf<VoiceError>()
        backgroundScope.launchCollect(rig.playback, errors)
        runCurrent()

        rig.playback.toggle(row)
        runCurrent()
        assertThat(errors).containsExactly(VoiceError.FAILED)
        assertThat(rig.playback.playing.value).isNull()
    }

    /**
     * 收集错误。
     *
     * @param playback 被测对象
     * @param into 收集到的错误
     */
    private fun kotlinx.coroutines.CoroutineScope.launchCollect(playback: VoicePlayback, into: MutableList<VoiceError>) {
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { playback.errors.collect { into += it } }
    }

    private companion object {
        const val BOB = "10002@im.example.com"
    }
}
