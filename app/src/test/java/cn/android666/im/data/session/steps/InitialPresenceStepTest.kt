package cn.android666.im.data.session.steps

import cn.android666.im.core.xmpp.OwnPresence
import cn.android666.im.data.session.SessionContext
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [InitialPresenceStep] 测试：初始 presence 带上本人保存的状态（开发计划 3.20） */
class InitialPresenceStepTest {

    @Test
    fun sendsSavedStatus() = runTest {
        val client = FakeXmppClient()
        val saved = OwnPresence(show = "dnd", status = "开会", code = "meeting")

        InitialPresenceStep(client) { saved }.run(SessionContext("10001"))

        assertThat(client.lastPresence).isEqualTo(saved)
    }

    @Test
    fun defaultAvailable() = runTest {
        val client = FakeXmppClient()
        InitialPresenceStep(client).run(SessionContext("10001"))
        assertThat(client.lastPresence).isEqualTo(OwnPresence.AVAILABLE)
    }
}
