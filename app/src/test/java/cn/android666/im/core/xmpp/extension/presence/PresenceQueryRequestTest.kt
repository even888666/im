package cn.android666.im.core.xmpp.extension.presence

import cn.android666.im.core.xmpp.iq.XmlNode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

/** 在线状态查询测试（协议规范 §5.3 示例） */
class PresenceQueryRequestTest {

    @Test
    fun request() {
        assertThat(PresenceQueryRequest(listOf("10086@im.example.com", "10087@im.example.com")).toChildXml()).isEqualTo(
            "<presence-query xmlns='urn:android666:im:presence:1'>" +
                "<item jid='10086@im.example.com'/><item jid='10087@im.example.com'/></presence-query>",
        )
    }

    @Test
    fun parseProtocolResult() {
        val items = PresenceQueryRequest(emptyList()).parseResult(
            XmlNode.parse(
                """
                <presence-query xmlns='urn:android666:im:presence:1'>
                  <item jid='10086@im.example.com' online='true'>
                    <resource name='android' show='dnd' status='下午 3 点前在开会' code='meeting'/>
                    <resource name='pc'/>
                  </item>
                  <item jid='10087@im.example.com' online='false' lastActive='2026-10-13T01:20:00.000Z'/>
                </presence-query>
                """.trimIndent(),
            ),
        )
        assertThat(items[0]).isEqualTo(
            PresenceQueryItem(
                jid = "10086@im.example.com",
                online = true,
                resources = listOf(
                    ResourcePresence("android", "dnd", "下午 3 点前在开会", "meeting"),
                    ResourcePresence("pc", null, null, null),
                ),
                lastActiveMillis = null,
            ),
        )
        assertThat(items[1].online).isFalse()
        assertThat(items[1].lastActiveMillis).isEqualTo(Instant.parse("2026-10-13T01:20:00Z").toEpochMilli())
    }

    /** 时间格式错误按未知处理 */
    @Test
    fun badTime_null() {
        val items = PresenceQueryRequest(emptyList()).parseResult(
            XmlNode.parse("<presence-query xmlns='urn:android666:im:presence:1'><item jid='a@b' lastActive='x'/></presence-query>"),
        )
        assertThat(items.single().lastActiveMillis).isNull()
    }
}
