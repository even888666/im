package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.smack.StanzaLogFilter.Kind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [StanzaLogFilter] 噪声报文判断测试 */
class StanzaLogFilterTest {

    private val filter = StanzaLogFilter()

    @Test
    fun streamManagementAck_isNoise() {
        assertThat(filter.classify("<r xmlns='urn:xmpp:sm:3'/>")).isEqualTo(Kind.SM_ACK)
        assertThat(filter.classify("<a xmlns=\"urn:xmpp:sm:3\" h=\"12\"/>")).isEqualTo(Kind.SM_ACK)
    }

    /** 发出 ping 后，同 id 的空 result 算作心跳响应 */
    @Test
    fun pingAndItsResult_areNoise() {
        assertThat(filter.classify("<iq id='p1' type='get' to='im.example.com'><ping xmlns='urn:xmpp:ping'/></iq>"))
            .isEqualTo(Kind.PING)
        assertThat(filter.classify("<iq type='result' id='p1' from='im.example.com'/>")).isEqualTo(Kind.PING_RESULT)
        // 同一个 id 只算一次
        assertThat(filter.classify("<iq type='result' id='p1' from='im.example.com'/>")).isEqualTo(Kind.LOG)
    }

    /** 服务器发来的 ping 也算心跳 */
    @Test
    fun serverPing_isNoise() {
        assertThat(filter.classify("<iq from='im.example.com' id=\"s1\" type=\"get\"><ping xmlns=\"urn:xmpp:ping\"/></iq>"))
            .isEqualTo(Kind.PING)
    }

    /** 其他 id 的空 result 正常记录（可能是别的业务请求的响应） */
    @Test
    fun otherResult_isLogged() {
        assertThat(filter.classify("<iq type='result' id='q9'/>")).isEqualTo(Kind.LOG)
    }

    /** 正文中出现 sm 命名空间或 ping 字样的消息正常记录 */
    @Test
    fun messages_areLogged() {
        assertThat(filter.classify("<message><body>&lt;r xmlns='urn:xmpp:sm:3'/&gt;</body></message>")).isEqualTo(Kind.LOG)
        assertThat(filter.classify("<message><body>urn:xmpp:ping</body></message>")).isEqualTo(Kind.LOG)
        assertThat(filter.classify("<enabled xmlns='urn:xmpp:sm:3' resume='true' id='x'/>")).isEqualTo(Kind.LOG)
    }

    /** 服务器不响应 ping 时，记录的 id 数量有上限 */
    @Test
    fun pendingPings_bounded() {
        repeat(100) { filter.classify("<iq id='p$it' type='get'><ping xmlns='urn:xmpp:ping'/></iq>") }
        // 最早的 id 已被淘汰，响应按普通报文记录；最近的仍能识别
        assertThat(filter.classify("<iq type='result' id='p0'/>")).isEqualTo(Kind.LOG)
        assertThat(filter.classify("<iq type='result' id='p99'/>")).isEqualTo(Kind.PING_RESULT)
    }
}
