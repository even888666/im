package cn.android666.im.ui.main

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.data.avatar.AvatarRepository
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.data.presence.MyStatus
import cn.android666.im.data.presence.MyStatusSetting
import cn.android666.im.data.presence.PresenceRepository
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeXmppClient
import cn.android666.im.testing.MainDispatcherRule
import cn.android666.im.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** [MeViewModel] 测试：修改本人状态、更换头像（原生图形模式：图片解码与真机一致） */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val holder = UserDatabaseHolder {
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImDatabase::class.java).build()
    }

    @After
    fun tearDown() = holder.close()

    @Test
    fun setStatus_savedAndMessage() = runTest(mainDispatcherRule.testDispatcher) {
        holder.open("10001")
        val client = FakeXmppClient()
        val repository = PresenceRepository(client, holder, FakeClock())
        val folder = java.io.File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "avatars-test")
        val vm = MeViewModel(
            repository,
            OrgRepository(client, holder, FakeClock()),
            AvatarRepository(client, folder, TestDispatcherProvider(mainDispatcherRule.testDispatcher), backgroundScope),
            "10001@im.example.com",
        )

        vm.messages.test {
            vm.setStatus(MyStatusSetting(MyStatus.BUSY, "赶项目"))
            assertThat(awaitItem()).isEqualTo(R.string.me_status_saved)
        }
        assertThat(vm.myStatus.value).isEqualTo(MyStatusSetting(MyStatus.BUSY, "赶项目"))
    }

    /** 无法解码的图片：提示换一张，不上传 */
    @Test
    fun setAvatar_invalidImage() = runTest(mainDispatcherRule.testDispatcher) {
        holder.open("10001")
        val client = FakeXmppClient()
        val folder = java.io.File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "avatars-test")
        val vm = MeViewModel(
            PresenceRepository(client, holder, FakeClock()),
            OrgRepository(client, holder, FakeClock()),
            AvatarRepository(client, folder, TestDispatcherProvider(mainDispatcherRule.testDispatcher), backgroundScope),
            "10001@im.example.com",
        )
        vm.messages.test {
            vm.setAvatar(byteArrayOf(1, 2, 3))
            assertThat(awaitItem()).isEqualTo(R.string.me_avatar_invalid)
        }
        assertThat(client.calls.none { it.startsWith("avatar:set") }).isTrue()
    }
}
