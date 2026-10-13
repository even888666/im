package cn.android666.im.data.message

import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.xmpp.extension.msg.FileInfo
import cn.android666.im.core.xmpp.extension.msg.Mention
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [MessageJson] 与 [OutgoingStanzaBuilder] 测试 */
class MessageJsonTest {

    @Test
    fun content_textNotStored() {
        assertThat(MessageJson.encodeContent(MessageContent.Text)).isNull()
        assertThat(MessageJson.decodeContent("text", null)).isEqualTo(MessageContent.Text)
    }

    @Test
    fun content_roundTrip() {
        val content = MessageContent.File(FileInfo("u", "a.pdf", 1, "application/pdf"))

        assertThat(MessageJson.decodeContent("file", MessageJson.encodeContent(content))).isEqualTo(content)
    }

    /** JSON 缺失或损坏：按未知类型处理，不崩溃 */
    @Test
    fun content_brokenJson_unknown() {
        assertThat(MessageJson.decodeContent("image", null)).isEqualTo(MessageContent.Unknown("image"))
        assertThat(MessageJson.decodeContent("image", "{oops")).isEqualTo(MessageContent.Unknown("image"))
        assertThat(MessageJson.decodeContent("image", """{"type":"image"}""")).isEqualTo(MessageContent.Unknown("image"))
        assertThat(MessageJson.decodeContent("x", """{"type":"future-type"}""")).isEqualTo(MessageContent.Unknown("x"))
    }

    @Test
    fun mentions_roundTripAndBroken() {
        val mentions = listOf(Mention(0, 3, "a@b"), Mention(4, 8, "g@conference.b"))

        assertThat(MessageJson.encodeMentions(emptyList())).isNull()
        assertThat(MessageJson.decodeMentions(MessageJson.encodeMentions(mentions))).isEqualTo(mentions)
        assertThat(MessageJson.decodeMentions(null)).isEmpty()
        assertThat(MessageJson.decodeMentions("[{")).isEmpty()
    }

    /** 从消息表记录生成报文：内容、引用、@ 都还原 */
    @Test
    fun outgoingStanza_fromEntity() {
        val content = MessageContent.Card("10003@im.example.com", "王五")
        val entity = MessageEntity(
            msgId = "m1",
            conversationId = "g1@conference.im.example.com",
            senderJid = "10001@im.example.com",
            direction = MessageEntity.DIRECTION_OUT,
            type = content.type,
            body = "[名片] 王五",
            contentJson = MessageJson.encodeContent(content),
            status = MessageEntity.STATUS_SENDING,
            serverTime = 1,
            replyToMsgId = "orig",
            mentionsJson = MessageJson.encodeMentions(listOf(Mention(0, 1, "10002@im.example.com"))),
        )

        val stanza = MessageCodec.parse(OutgoingStanzaBuilder.build(entity, groupChat = true, replyToSender = "10002@im.example.com")).message

        assertThat(stanza.type).isEqualTo("groupchat")
        assertThat(stanza.id).isEqualTo("m1")
        assertThat(stanza.to).isEqualTo("g1@conference.im.example.com")
        assertThat(stanza.content).isEqualTo(content)
        assertThat(stanza.reply?.to).isEqualTo("10002@im.example.com")
        assertThat(stanza.mentions.single().jid).isEqualTo("10002@im.example.com")
        // 找不到原消息的发送方：不带引用
        assertThat(MessageCodec.parse(OutgoingStanzaBuilder.build(entity, true, null)).message.reply).isNull()
    }
}
