package cn.android666.im.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.data.presence.ColleaguePresence
import cn.android666.im.data.presence.MyStatus
import cn.android666.im.data.presence.ResourceStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [PresenceText] 测试：资料卡上的在线状态描述 */
@RunWith(RobolectricTestRunner::class)
class PresenceTextTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun online_statusDevicesText() {
        val presence = ColleaguePresence(
            "a@b", true,
            listOf(ResourceStatus("android", MyStatus.MEETING, "下午 3 点前在开会"), ResourceStatus("pc", MyStatus.ONLINE, null)),
            null,
        )
        assertThat(PresenceText.describe(context, presence, 0)).isEqualTo("开会中 · 手机、电脑 · 下午 3 点前在开会")
    }

    @Test
    fun offline_lastActive() {
        val now = 1_760_000_000_000L
        val presence = ColleaguePresence("a@b", false, emptyList(), now - 2 * 60 * 60 * 1000)
        val text = PresenceText.describe(context, presence, now)
        assertThat(text).startsWith("离线 · 最后在线：")
        assertThat(PresenceText.describe(context, presence.copy(lastActiveMillis = null), now)).isEqualTo("离线")
    }
}
