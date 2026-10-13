package cn.android666.im.data.avatar

import android.graphics.Bitmap
import android.graphics.Color
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.testing.FakeXmppClient
import cn.android666.im.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** [AvatarRepository] 测试：按哈希缓存、去重下载、没有头像不重复请求、设置本人头像 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val client = FakeXmppClient()
    private val jid = "10002@im.example.com"
    private val photo = "头像数据".toByteArray()
    private val hash = AvatarRepository.sha1(photo)

    @Test
    fun download_thenCached() = runTest {
        val repository = AvatarRepository(client, folder.root, TestDispatcherProvider(StandardTestDispatcher(testScheduler)), backgroundScope)
        client.avatars[jid] = photo

        val file = repository.file(jid, hash)!!
        assertThat(file.readBytes()).isEqualTo(photo)
        assertThat(file.name).isEqualTo("$hash.jpg")

        // 已缓存：不再请求
        repository.file(jid, hash)
        assertThat(client.calls.count { it.startsWith("avatar:load") }).isEqualTo(1)
        assertThat(repository.file(jid, "")).isNull()
    }

    /** 同一个哈希同时请求时只下载一次 */
    @Test
    fun concurrentRequests_downloadOnce() = runTest {
        val repository = AvatarRepository(client, folder.root, TestDispatcherProvider(StandardTestDispatcher(testScheduler)), backgroundScope)
        client.avatars[jid] = photo

        val files = (1..5).map { async { repository.file(jid, hash) } }.awaitAll()

        assertThat(files.map { it?.name }.distinct()).containsExactly("$hash.jpg")
        assertThat(client.calls.count { it.startsWith("avatar:load") }).isEqualTo(1)
    }

    /** vCard 中没有头像：返回 null，之后不再请求同一个哈希 */
    @Test
    fun noAvatar_rememberedMissing() = runTest {
        val repository = AvatarRepository(client, folder.root, TestDispatcherProvider(StandardTestDispatcher(testScheduler)), backgroundScope)

        assertThat(repository.file(jid, hash)).isNull()
        assertThat(repository.file(jid, hash)).isNull()
        assertThat(client.calls.count { it.startsWith("avatar:load") }).isEqualTo(1)
    }

    /** 下载失败（网络）：返回 null，下次还会重试 */
    @Test
    fun networkError_retriedLater() = runTest {
        val repository = AvatarRepository(client, folder.root, TestDispatcherProvider(StandardTestDispatcher(testScheduler)), backgroundScope)
        client.nextRequestError = XmppException.Timeout()
        assertThat(repository.file(jid, hash)).isNull()

        client.avatars[jid] = photo
        assertThat(repository.file(jid, hash)).isNotNull()
    }

    @Test
    fun setMyAvatar_uploadsJpegAndCaches() = runTest {
        val repository = AvatarRepository(client, folder.root, TestDispatcherProvider(StandardTestDispatcher(testScheduler)), backgroundScope)
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }

        val newHash = repository.setMyAvatar(png)

        val uploaded = client.avatars[client.selfJid]!!
        assertThat(AvatarRepository.sha1(uploaded)).isEqualTo(newHash)
        assertThat(client.calls).contains("avatar:set:image/jpeg")
        // 已放入缓存，不需要再下载
        assertThat(repository.file(client.selfJid, newHash)!!.readBytes()).isEqualTo(uploaded)
        assertThat(client.calls.none { it.startsWith("avatar:load") }).isTrue()
    }

    @Test
    fun sha1_matchesXep0153() {
        assertThat(AvatarRepository.sha1("hello".toByteArray())).isEqualTo("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")
    }
}
