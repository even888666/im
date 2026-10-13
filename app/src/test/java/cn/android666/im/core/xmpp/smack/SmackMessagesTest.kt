package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.extension.msg.FileInfo
import cn.android666.im.core.xmpp.extension.msg.ForwardedItem
import cn.android666.im.core.xmpp.extension.msg.Mention
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.extension.msg.ReplyRef
import cn.android666.im.core.xmpp.extension.msg.ThumbnailInfo
import com.google.common.truth.Truth.assertThat
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.util.PacketParserUtils
import org.junit.Assert.assertThrows
import org.junit.Test
import org.jxmpp.JxmppContext

/**
 * [SmackMessages] 测试：MessageCodec 生成的报文经过 Smack 发送、Smack 解析收到的报文再转回 XML，
 * 内容都不能丢失（Smack 中一些类型化的扩展元素重新序列化时会丢掉属性）。
 */
class SmackMessagesTest {

    private val file = FileInfo("https://h/a?x=1&y=2", "a&b.jpg", 10, "image/jpeg", sha256 = "s", width = 2, height = 3)
    private val thumb = ThumbnailInfo("https://h/t.jpg", 1, 2, "image/jpeg")

    /** 发送方向：各类报文转为 Smack 消息后序列化，解析结果与原报文一致 */
    @Test
    fun build_isLossless() {
        val samples = listOf(
            MessageCodec.chat("10087@im.example.com", "t1", "  你好 <&>  ", MessageContent.Text),
            MessageCodec.chat("10087@im.example.com", "i1", "[图片]", MessageContent.Image(file, thumb, original = true)),
            MessageCodec.chat(
                "10087@im.example.com",
                "m1",
                "[聊天记录] t",
                MessageContent.Merged(
                    "t",
                    listOf(ForwardedItem("o1", "10086@im.example.com", "hi", MessageContent.Card("10088@im.example.com", "王五"), 1_000)),
                ),
            ),
            MessageCodec.chat("10087@im.example.com", "r1", "好的", MessageContent.Text, reply = ReplyRef("10087@im.example.com", "5b1e")),
            MessageCodec.chat(
                "g1@conference.im.example.com",
                "g1",
                "@张三 下午开会",
                MessageContent.Text,
                groupChat = true,
                mentions = listOf(Mention(0, 3, "10086@im.example.com")),
            ),
            MessageCodec.receipt("10086@im.example.com/pc", "5b1e", "rc1"),
            MessageCodec.displayed("10086@im.example.com", "5b1e", "d1"),
            MessageCodec.retract("10087@im.example.com", "5b1e", "张三撤回了一条消息", "x1"),
            MessageCodec.chatState("10087@im.example.com", "composing", "c1"),
        )

        for (xml in samples) {
            val message = SmackMessages.build(xml)
            val roundTrip = SmackMessages.toXml(message)
            assertThat(MessageCodec.parse(roundTrip)).isEqualTo(MessageCodec.parse(xml))
        }
    }

    @Test
    fun build_setsIdTypeAndTo() {
        val message = SmackMessages.build(MessageCodec.chat("10087@im.example.com", "t1", "x", MessageContent.Text, groupChat = false))

        assertThat(message.stanzaId).isEqualTo("t1")
        assertThat(message.type).isEqualTo(Message.Type.chat)
        assertThat(message.to.toString()).isEqualTo("10087@im.example.com")
        assertThat(message.body).isEqualTo("x")
    }

    /** 撤回报文中 fallback 的 for 属性必须保留（Smack 的 FallbackIndicationElement 不认识它） */
    @Test
    fun build_keepsFallbackFor() {
        val xml = SmackMessages.toXml(SmackMessages.build(MessageCodec.retract("a@b", "5b1e", "x", "x1")))

        assertThat(xml).contains("for='urn:xmpp:message-retract:1'")
    }

    @Test
    fun build_invalid_throws() {
        assertThrows(IllegalArgumentException::class.java) { SmackMessages.build("<message type='chat'><body>x</body></message>") }
        assertThrows(IllegalArgumentException::class.java) { SmackMessages.build("<iq id='a'/>") }
        assertThrows(IllegalArgumentException::class.java) { SmackMessages.build("<message id='a' type='bogus'/>") }
    }

    /** 接收方向：Smack 用自带的扩展解析器解析协议示例，再序列化，MessageCodec 的解析结果不变 */
    @Test
    fun receivedBySmack_isLossless() {
        val samples = listOf(
            // §6.1 + §8.5 引用
            "<message xmlns='jabber:client' type='chat' id='5b1e' from='10086@im.example.com/android' to='10087@im.example.com'>" +
                "<body>你好</body><origin-id xmlns='urn:xmpp:sid:0' id='5b1e'/>" +
                "<reply xmlns='urn:xmpp:reply:0' to='10087@im.example.com' id='p1'/>" +
                "<content xmlns='urn:android666:im:msg:1' type='text'/>" +
                "<request xmlns='urn:xmpp:receipts'/><markable xmlns='urn:xmpp:chat-markers:0'/>" +
                "<stanza-id xmlns='urn:xmpp:sid:0' id='s-1' by='10087@im.example.com'/></message>",
            // §7.2 图片 + 离线 delay
            "<message xmlns='jabber:client' type='chat' id='i1' from='10086@im.example.com/pc'><body>[图片]</body>" +
                "<content xmlns='urn:android666:im:msg:1' type='image' original='false'>" +
                "<file url='u' name='a.jpg' size='245760' mime='image/jpeg' width='1920' height='1080'/>" +
                "<thumbnail url='t' width='320' height='180' mime='image/jpeg'/></content>" +
                "<delay xmlns='urn:xmpp:delay' from='im.example.com' stamp='2026-10-13T01:00:00.000Z'/></message>",
            // §8.1 回执、§8.2 已读、§8.3 正在输入
            "<message xmlns='jabber:client' id='r1' from='10087@im.example.com/pc'><received xmlns='urn:xmpp:receipts' id='5b1e'/>" +
                "<no-store xmlns='urn:xmpp:hints'/></message>",
            "<message xmlns='jabber:client' type='chat' id='d1' from='10087@im.example.com/pc'>" +
                "<displayed xmlns='urn:xmpp:chat-markers:0' id='5b1e'/><store xmlns='urn:xmpp:hints'/></message>",
            "<message xmlns='jabber:client' type='chat' id='c1' from='10087@im.example.com/pc'>" +
                "<composing xmlns='http://jabber.org/protocol/chatstates'/></message>",
            // §8.4 撤回
            "<message xmlns='jabber:client' type='chat' id='x1' from='10087@im.example.com/pc'><body>张三撤回了一条消息</body>" +
                "<retract xmlns='urn:xmpp:message-retract:1' id='5b1e'/>" +
                "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:message-retract:1'/><store xmlns='urn:xmpp:hints'/></message>",
            // §8.6 @
            "<message xmlns='jabber:client' type='groupchat' id='g1' from='g1@conference.im.example.com/10086'>" +
                "<body>@张三 @所有人 下午开会</body>" +
                "<reference xmlns='urn:xmpp:reference:0' type='mention' begin='0' end='3' uri='xmpp:10086@im.example.com'/>" +
                "<reference xmlns='urn:xmpp:reference:0' type='mention' begin='4' end='8' uri='xmpp:g1@conference.im.example.com'/>" +
                "</message>",
            // §6.4 敏感词
            "<message xmlns='jabber:client' type='error' id='5b1e' from='10087@im.example.com'><error type='modify'>" +
                "<policy-violation xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                "<app-error xmlns='urn:android666:im:error:1' code='sensitive-word'/></error></message>",
            // §11.1 Carbons
            "<message xmlns='jabber:client' from='10086@im.example.com' to='10086@im.example.com/android' type='chat'>" +
                "<sent xmlns='urn:xmpp:carbons:2'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message xmlns='jabber:client' type='chat' id='c1' from='10086@im.example.com/pc' to='10087@im.example.com'>" +
                "<body>PC 端发的</body><origin-id xmlns='urn:xmpp:sid:0' id='c1'/></message></forwarded></sent></message>",
            // §11.2 MAM
            "<message xmlns='jabber:client' to='10086@im.example.com/android'>" +
                "<result xmlns='urn:xmpp:mam:2' queryid='q1' id='s-100'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<delay xmlns='urn:xmpp:delay' stamp='2026-10-13T01:00:00.000Z'/>" +
                "<message xmlns='jabber:client' type='chat' id='h1' from='10087@im.example.com/pc' to='10086@im.example.com'>" +
                "<body>历史消息</body><request xmlns='urn:xmpp:receipts'/></message></forwarded></result></message>",
        )

        for (xml in samples) {
            val parser = PacketParserUtils.getParserFor(xml)
            val message = PacketParserUtils.parseMessage(parser, XmlEnvironment.EMPTY, JxmppContext.getDefaultContext())
            val expected = MessageCodec.parse(xml)
            assertThat(MessageCodec.parse(SmackMessages.toXml(message))).isEqualTo(expected)
        }
    }
}
