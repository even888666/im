package cn.android666.im.data.message

import app.cash.turbine.test
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.UuidIdGenerator
import cn.android666.im.core.xmpp.Credential
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.testing.FakeXmppClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [ChatStates] 测试：协议规范 §8.3 的节流与 paused 规则、对方状态的显示与到期 */
class ChatStatesTest {

    private val client = FakeXmppClient()

    private fun TestScope.states(): ChatStates {
        val clock = object : Clock {
            override fun nowMillis() = testScheduler.currentTime
            override fun elapsedMillis() = testScheduler.currentTime
        }
        return ChatStates(client, UuidIdGenerator(), clock, backgroundScope)
    }

    /** 已发送的状态（按顺序） */
    private fun sentStates(): List<String?> = client.sentMessages.map { MessageCodec.parse(it).message.chatState }

    @Test
    fun composing_throttledTo5s_pausedAfter5sIdle() = runTest {
        client.login(Credential("10001", "x"))
        val states = states()

        states.onTyping(BOB)
        runCurrent()
        advanceTimeBy(1_000)
        states.onTyping(BOB)
        advanceTimeBy(1_000)
        states.onTyping(BOB)
        runCurrent()
        assertThat(sentStates()).containsExactly("composing")

        // 距上次 composing 满 5 秒：再发一次
        advanceTimeBy(3_000)
        states.onTyping(BOB)
        runCurrent()
        assertThat(sentStates()).containsExactly("composing", "composing")

        // 停止输入 5 秒：paused
        advanceTimeBy(4_999)
        assertThat(sentStates()).hasSize(2)
        advanceTimeBy(2)
        assertThat(sentStates()).containsExactly("composing", "composing", "paused").inOrder()
        val paused = MessageCodec.parse(client.sentMessages.last()).message
        assertThat(paused.to).isEqualTo(BOB)
        assertThat(client.sentMessages.last()).contains("<no-store xmlns='urn:xmpp:hints'/>")
    }

    /** 发出消息 / 离开页面：发过 composing 时立即 paused；没发过时不发 */
    @Test
    fun stopTyping() = runTest {
        client.login(Credential("10001", "x"))
        val states = states()

        states.onStopTyping(BOB)
        runCurrent()
        assertThat(client.sentMessages).isEmpty()

        states.onTyping(BOB)
        states.onStopTyping(BOB)
        runCurrent()
        assertThat(sentStates()).containsExactly("composing", "paused").inOrder()

        // 计时任务已取消，不会再发 paused
        advanceTimeBy(10_000)
        assertThat(sentStates()).hasSize(2)
    }

    /** 未连接时发送失败：不影响调用方 */
    @Test
    fun notConnected_ignored() = runTest {
        val states = states()
        states.onTyping(BOB)
        runCurrent()
        assertThat(client.sentMessages).isEmpty()
    }

    /** 对方状态：composing 显示；paused 消失；30 秒没有新的 composing 自动消失；对方发来消息时消失 */
    @Test
    fun peerTyping() = runTest {
        val states = states()

        states.observePeerTyping(BOB).test {
            assertThat(awaitItem()).isFalse()

            states.onPeerState(BOB, "composing")
            assertThat(awaitItem()).isTrue()
            states.onPeerState(BOB, "paused")
            assertThat(awaitItem()).isFalse()

            states.onPeerState(BOB, "composing")
            assertThat(awaitItem()).isTrue()
            advanceTimeBy(ChatStates.EXPIRE_MILLIS + 1)
            assertThat(awaitItem()).isFalse()

            states.onPeerState(BOB, "composing")
            assertThat(awaitItem()).isTrue()
            // 新的 composing 重新计时
            advanceTimeBy(20_000)
            states.onPeerState(BOB, "composing")
            advanceTimeBy(20_000)
            expectNoEvents()
            states.onPeerMessage(BOB)
            assertThat(awaitItem()).isFalse()

            // 其他会话不影响
            states.onPeerState("10003@im.example.com", "composing")
            expectNoEvents()
        }
    }

    private companion object {
        const val BOB = "10002@im.example.com"
    }
}
