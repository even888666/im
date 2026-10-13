package cn.android666.im.ui.main

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import cn.android666.im.R
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.data.presence.MyStatus
import cn.android666.im.data.presence.MyStatusSetting
import cn.android666.im.data.presence.PresenceRepository
import cn.android666.im.testing.FakeClock
import cn.android666.im.testing.FakeXmppClient
import cn.android666.im.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [MeViewModel] 测试：修改本人状态 */
@RunWith(RobolectricTestRunner::class)
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
        val repository = PresenceRepository(FakeXmppClient(), holder, FakeClock())
        val vm = MeViewModel(repository)

        vm.messages.test {
            vm.setStatus(MyStatusSetting(MyStatus.BUSY, "赶项目"))
            assertThat(awaitItem()).isEqualTo(R.string.me_status_saved)
        }
        assertThat(vm.myStatus.value).isEqualTo(MyStatusSetting(MyStatus.BUSY, "赶项目"))
    }
}
