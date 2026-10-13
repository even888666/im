package cn.android666.im.core.xmpp.extension.msg

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [MessageCodec] 测试：直接使用《三端协议规范.md》§6 ~ §8、§11 中的示例报文。
 */
class MessageCodecTest {

    /** 2026-10-13T01:00:00.000Z 的毫秒数 */
    private val stamp1 = 1_791_853_200_000L

    // ---------------------------------------------------------------- 解析：内容类型

    /** §6.1 文本消息：body、origin-id、content、request、markable */
    @Test
    fun parse_textMessage() {
        val result = MessageCodec.parse(
            """
            <message xmlns='jabber:client' type='chat' id='5b1e' from='10086@im.example.com/android-1' to='10087@im.example.com'>
              <body>你好</body>
              <origin-id xmlns='urn:xmpp:sid:0' id='5b1e'/>
              <content xmlns='urn:android666:im:msg:1' type='text'/>
              <request xmlns='urn:xmpp:receipts'/>
              <markable xmlns='urn:xmpp:chat-markers:0'/>
            </message>
            """.trimIndent(),
        )

        assertThat(result.envelope).isNull()
        val msg = result.message
        assertThat(msg.id).isEqualTo("5b1e")
        assertThat(msg.msgId).isEqualTo("5b1e")
        assertThat(msg.type).isEqualTo("chat")
        assertThat(msg.from).isEqualTo("10086@im.example.com/android-1")
        assertThat(msg.to).isEqualTo("10087@im.example.com")
        assertThat(msg.body).isEqualTo("你好")
        assertThat(msg.content).isEqualTo(MessageContent.Text)
        assertThat(msg.originId).isEqualTo("5b1e")
        assertThat(msg.requestReceipt).isTrue()
        assertThat(msg.markable).isTrue()
        assertThat(msg.error).isNull()
    }

    /** content 缺省等同于文本；正文首尾空白原样保留 */
    @Test
    fun parse_withoutContent_isTextAndKeepsWhitespace() {
        val msg = MessageCodec.parse("<message type='chat' id='a'><body>  第一行\n第二行  </body></message>").message

        assertThat(msg.content).isEqualTo(MessageContent.Text)
        assertThat(msg.body).isEqualTo("  第一行\n第二行  ")
        assertThat(msg.requestReceipt).isFalse()
        assertThat(msg.markable).isFalse()
    }

    /** 没有 message@id 时用 origin-id */
    @Test
    fun parse_missingId_usesOriginId() {
        val msg = MessageCodec.parse("<message type='chat'><body>x</body><origin-id xmlns='urn:xmpp:sid:0' id='o1'/></message>").message

        assertThat(msg.id).isNull()
        assertThat(msg.msgId).isEqualTo("o1")
    }

    /** 没有 type 属性：normal */
    @Test
    fun parse_missingType_isNormal() {
        assertThat(MessageCodec.parse("<message id='a'/>").message.type).isEqualTo("normal")
    }

    /** §7.2 图片 */
    @Test
    fun parse_image() {
        val content = parseContent(
            """
            <content xmlns='urn:android666:im:msg:1' type='image' original='false'>
              <file url='https://im.example.com:7443/httpfileupload/x/a.jpg' name='a.jpg'
                    size='245760' mime='image/jpeg' width='1920' height='1080'/>
              <thumbnail url='https://im.example.com:7443/httpfileupload/x/a_thumb.jpg'
                         width='320' height='180' mime='image/jpeg'/>
            </content>
            """,
        )

        assertThat(content).isEqualTo(
            MessageContent.Image(
                file = FileInfo(
                    url = "https://im.example.com:7443/httpfileupload/x/a.jpg",
                    name = "a.jpg",
                    size = 245760,
                    mime = "image/jpeg",
                    width = 1920,
                    height = 1080,
                ),
                thumbnail = ThumbnailInfo("https://im.example.com:7443/httpfileupload/x/a_thumb.jpg", 320, 180, "image/jpeg"),
                original = false,
            ),
        )
    }

    /** 原图标记 */
    @Test
    fun parse_imageOriginal() {
        val content = parseContent(
            "<content xmlns='urn:android666:im:msg:1' type='image' original='true'>" +
                "<file url='u' name='a.png' size='1' mime='image/png' width='1' height='1'/></content>",
        ) as MessageContent.Image

        assertThat(content.original).isTrue()
        assertThat(content.thumbnail).isNull()
    }

    /** §7.3 语音 */
    @Test
    fun parse_voice() {
        val content = parseContent(
            "<content xmlns='urn:android666:im:msg:1' type='voice'>" +
                "<file url='u' name='voice.m4a' size='16384' mime='audio/mp4' duration='5300'/></content>",
        )

        assertThat(content).isEqualTo(MessageContent.Voice(FileInfo("u", "voice.m4a", 16384, "audio/mp4", duration = 5300)))
    }

    /** §7.4 视频 */
    @Test
    fun parse_video() {
        val content = parseContent(
            """
            <content xmlns='urn:android666:im:msg:1' type='video'>
              <file url='u' name='v.mp4' size='5242880' mime='video/mp4' width='1280' height='720' duration='15000'/>
              <thumbnail url='t' width='320' height='180' mime='image/jpeg'/>
            </content>
            """,
        )

        assertThat(content).isEqualTo(
            MessageContent.Video(
                FileInfo("u", "v.mp4", 5242880, "video/mp4", width = 1280, height = 720, duration = 15000),
                ThumbnailInfo("t", 320, 180, "image/jpeg"),
            ),
        )
    }

    /** §7.5 文件 */
    @Test
    fun parse_file() {
        val content = parseContent(
            "<content xmlns='urn:android666:im:msg:1' type='file'>" +
                "<file url='u' name='季度报告.pdf' size='1048576' mime='application/pdf' sha256='abc'/></content>",
        )

        assertThat(content).isEqualTo(MessageContent.File(FileInfo("u", "季度报告.pdf", 1048576, "application/pdf", sha256 = "abc")))
    }

    /** §7.6 名片 */
    @Test
    fun parse_card() {
        val content = parseContent(
            "<content xmlns='urn:android666:im:msg:1' type='card'><card jid='10086@im.example.com' name='张三'/></content>",
        )

        assertThat(content).isEqualTo(MessageContent.Card("10086@im.example.com", "张三"))
    }

    /** §7.7 合并转发：每条 forwarded 的 delay、发送方、正文、原 content */
    @Test
    fun parse_merged() {
        val content = parseContent(
            """
            <content xmlns='urn:android666:im:msg:1' type='merged' title='张三和李四的聊天记录'>
              <forwarded xmlns='urn:xmpp:forward:0'>
                <delay xmlns='urn:xmpp:delay' stamp='2026-10-13T01:00:00.000Z'/>
                <message xmlns='jabber:client' type='chat' id='m1' from='10086@im.example.com' to='10087@im.example.com'>
                  <body>原始消息内容</body>
                </message>
              </forwarded>
              <forwarded xmlns='urn:xmpp:forward:0'>
                <message xmlns='jabber:client' type='chat' id='m2' from='10087@im.example.com'>
                  <body>[名片] 王五</body>
                  <content xmlns='urn:android666:im:msg:1' type='card'><card jid='10088@im.example.com' name='王五'/></content>
                </message>
              </forwarded>
            </content>
            """,
        ) as MessageContent.Merged

        assertThat(content.title).isEqualTo("张三和李四的聊天记录")
        assertThat(content.items).containsExactly(
            ForwardedItem("m1", "10086@im.example.com", "原始消息内容", MessageContent.Text, stamp1),
            ForwardedItem("m2", "10087@im.example.com", "[名片] 王五", MessageContent.Card("10088@im.example.com", "王五"), null),
        ).inOrder()
    }

    /** §7.8 通话记录 */
    @Test
    fun parse_call() {
        val content = parseContent(
            "<content xmlns='urn:android666:im:msg:1' type='call'>" +
                "<call callId='c-7f3a' media='video' result='completed' duration='205'/></content>",
        )

        assertThat(content).isEqualTo(MessageContent.Call("c-7f3a", "video", "completed", 205))
    }

    /** 未接通的通话没有 duration */
    @Test
    fun parse_callWithoutDuration() {
        val content = parseContent(
            "<content xmlns='urn:android666:im:msg:1' type='call'><call callId='c1' media='audio' result='missed'/></content>",
        )

        assertThat(content).isEqualTo(MessageContent.Call("c1", "audio", "missed", null))
    }

    /** §1.5：不认识的类型（如预留的 custom）→ Unknown，界面显示 body */
    @Test
    fun parse_unknownType() {
        val msg = MessageCodec.parse(
            "<message type='chat' id='a'><body>请升级</body>" +
                "<content xmlns='urn:android666:im:msg:1' type='custom'><biz kind='approval'/></content></message>",
        ).message

        assertThat(msg.content).isEqualTo(MessageContent.Unknown("custom"))
        assertThat(msg.body).isEqualTo("请升级")
    }

    /** 必要子元素缺失（图片没有 file、名片没有 jid、通话缺属性）→ Unknown，不抛异常 */
    @Test
    fun parse_missingRequiredChild_isUnknown() {
        assertThat(parseContent("<content xmlns='urn:android666:im:msg:1' type='image'/>")).isEqualTo(MessageContent.Unknown("image"))
        assertThat(parseContent("<content xmlns='urn:android666:im:msg:1' type='image'><file name='a'/></content>"))
            .isEqualTo(MessageContent.Unknown("image"))
        assertThat(parseContent("<content xmlns='urn:android666:im:msg:1' type='card'><card name='张三'/></content>"))
            .isEqualTo(MessageContent.Unknown("card"))
        assertThat(parseContent("<content xmlns='urn:android666:im:msg:1' type='call'><call callId='c'/></content>"))
            .isEqualTo(MessageContent.Unknown("call"))
    }

    /** file 中缺少可选属性或数字格式错误：使用默认值 */
    @Test
    fun parse_fileDefaults() {
        val content = parseContent(
            "<content xmlns='urn:android666:im:msg:1' type='file'><file url='u' size='abc'/></content>",
        ) as MessageContent.File

        assertThat(content.file).isEqualTo(FileInfo("u", "", 0, "application/octet-stream"))
    }

    /** §1.5：不认识的元素、其他命名空间的同名元素一律忽略 */
    @Test
    fun parse_ignoresUnknownElements() {
        val msg = MessageCodec.parse(
            """
            <message type='chat' id='a'>
              <body>hi</body>
              <future xmlns='urn:android666:im:future:1'/>
              <content xmlns='urn:other' type='image'/>
              <request xmlns='urn:other'/>
              <reference xmlns='urn:xmpp:reference:0' type='data' begin='0' end='1' uri='xmpp:x@y'/>
            </message>
            """.trimIndent(),
        ).message

        assertThat(msg.content).isEqualTo(MessageContent.Text)
        assertThat(msg.requestReceipt).isFalse()
        assertThat(msg.mentions).isEmpty()
    }

    // ---------------------------------------------------------------- 解析：消息操作

    /** §8.1 送达回执：没有 body，content 为 null */
    @Test
    fun parse_receipt() {
        val msg = MessageCodec.parse(
            "<message to='10086@im.example.com' id='r-1' from='10087@im.example.com/pc'>" +
                "<received xmlns='urn:xmpp:receipts' id='5b1e'/><no-store xmlns='urn:xmpp:hints'/></message>",
        ).message

        assertThat(msg.receivedId).isEqualTo("5b1e")
        assertThat(msg.content).isNull()
        assertThat(msg.body).isNull()
    }

    /** §8.2 已读 */
    @Test
    fun parse_displayed() {
        val msg = MessageCodec.parse(
            "<message type='chat' to='10086@im.example.com' id='m-1'>" +
                "<displayed xmlns='urn:xmpp:chat-markers:0' id='5b1e'/><store xmlns='urn:xmpp:hints'/></message>",
        ).message

        assertThat(msg.displayedId).isEqualTo("5b1e")
        assertThat(msg.content).isNull()
    }

    /** §8.3 正在输入 */
    @Test
    fun parse_chatState() {
        val msg = MessageCodec.parse(
            "<message type='chat' id='c'><composing xmlns='http://jabber.org/protocol/chatstates'/></message>",
        ).message

        assertThat(msg.chatState).isEqualTo("composing")
    }

    /** §8.4 撤回：降级文本不当作文本内容 */
    @Test
    fun parse_retract() {
        val msg = MessageCodec.parse(
            """
            <message type='chat' to='10087@im.example.com' id='x-1'>
              <body>张三撤回了一条消息</body>
              <retract xmlns='urn:xmpp:message-retract:1' id='5b1e'/>
              <fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:message-retract:1'/>
              <store xmlns='urn:xmpp:hints'/>
            </message>
            """.trimIndent(),
        ).message

        assertThat(msg.retractId).isEqualTo("5b1e")
        assertThat(msg.content).isNull()
        assertThat(msg.body).isEqualTo("张三撤回了一条消息")
    }

    /** §8.5 引用回复 */
    @Test
    fun parse_reply() {
        val msg = MessageCodec.parse(
            """
            <message type='chat' to='10087@im.example.com' id='r'>
              <body>好的，我下午处理</body>
              <reply xmlns='urn:xmpp:reply:0' to='10087@im.example.com' id='5b1e'/>
              <content xmlns='urn:android666:im:msg:1' type='text'/>
            </message>
            """.trimIndent(),
        ).message

        assertThat(msg.reply).isEqualTo(ReplyRef("10087@im.example.com", "5b1e"))
        assertThat(msg.content).isEqualTo(MessageContent.Text)
    }

    /** §8.6 @ 提醒：用户 JID 与群 JID（@所有人）；属性不全的引用忽略 */
    @Test
    fun parse_mentions() {
        val msg = MessageCodec.parse(
            """
            <message type='groupchat' to='g1@conference.im.example.com' id='g'>
              <body>@张三 @所有人 下午开会</body>
              <reference xmlns='urn:xmpp:reference:0' type='mention' begin='0' end='3' uri='xmpp:10086@im.example.com'/>
              <reference xmlns='urn:xmpp:reference:0' type='mention' begin='4' end='8' uri='xmpp:g1@conference.im.example.com'/>
              <reference xmlns='urn:xmpp:reference:0' type='mention' begin='x' end='8' uri='xmpp:bad@im.example.com'/>
            </message>
            """.trimIndent(),
        ).message

        assertThat(msg.type).isEqualTo("groupchat")
        assertThat(msg.mentions).containsExactly(
            Mention(0, 3, "10086@im.example.com"),
            Mention(4, 8, "g1@conference.im.example.com"),
        ).inOrder()
    }

    /** §6.4 敏感词：error 中的标准错误条件与自定义错误码 */
    @Test
    fun parse_sensitiveWordError() {
        val msg = MessageCodec.parse(
            """
            <message xmlns='jabber:client' type='error' id='5b1e' from='10087@im.example.com'>
              <error type='modify'>
                <policy-violation xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>
                <app-error xmlns='urn:android666:im:error:1' code='sensitive-word'/>
              </error>
            </message>
            """.trimIndent(),
        ).message

        assertThat(msg.type).isEqualTo("error")
        assertThat(msg.msgId).isEqualTo("5b1e")
        assertThat(msg.error).isEqualTo(MessageError("policy-violation", "sensitive-word"))
    }

    /** 没有标准错误条件：undefined-condition；text 元素不当作条件 */
    @Test
    fun parse_errorWithoutCondition() {
        val msg = MessageCodec.parse(
            "<message type='error' id='a'><error type='cancel'>" +
                "<text xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'>oops</text></error></message>",
        ).message

        assertThat(msg.error).isEqualTo(MessageError("undefined-condition", null))
    }

    /** §11.3 离线消息的 delay；实时消息中服务器添加的 stanza-id */
    @Test
    fun parse_delayAndStanzaId() {
        val msg = MessageCodec.parse(
            """
            <message type='chat' id='a' from='10086@im.example.com/pc'>
              <body>离线消息</body>
              <delay xmlns='urn:xmpp:delay' from='im.example.com' stamp='2026-10-13T01:00:00.000Z'/>
              <stanza-id xmlns='urn:xmpp:sid:0' id='s-9' by='10087@im.example.com'/>
              <stanza-id xmlns='urn:xmpp:sid:0' id='s-bad'/>
            </message>
            """.trimIndent(),
        ).message

        assertThat(msg.delayStamp).isEqualTo(stamp1)
        assertThat(msg.stanzaIds).containsExactly(StanzaId("s-9", "10087@im.example.com"))
    }

    // ---------------------------------------------------------------- 解析：外层包装

    /** §11.1 Carbons sent：本人其他端发出的消息 */
    @Test
    fun parse_carbonSent() {
        val result = MessageCodec.parse(
            """
            <message xmlns='jabber:client' from='10086@im.example.com' to='10086@im.example.com/android-1' type='chat'>
              <sent xmlns='urn:xmpp:carbons:2'>
                <forwarded xmlns='urn:xmpp:forward:0'>
                  <message xmlns='jabber:client' type='chat' id='c1' from='10086@im.example.com/pc' to='10087@im.example.com'>
                    <body>PC 端发的</body>
                    <origin-id xmlns='urn:xmpp:sid:0' id='c1'/>
                  </message>
                </forwarded>
              </sent>
            </message>
            """.trimIndent(),
        )

        assertThat(result.envelope).isEqualTo(MessageEnvelope.Carbon(sent = true, outerFrom = "10086@im.example.com"))
        assertThat(result.message.id).isEqualTo("c1")
        assertThat(result.message.from).isEqualTo("10086@im.example.com/pc")
        assertThat(result.message.to).isEqualTo("10087@im.example.com")
        assertThat(result.message.body).isEqualTo("PC 端发的")
    }

    /** Carbons received：本人其他端收到的消息（含已读标记，用于多端已读同步） */
    @Test
    fun parse_carbonReceived() {
        val result = MessageCodec.parse(
            """
            <message from='10086@im.example.com' to='10086@im.example.com/android-1' type='chat'>
              <received xmlns='urn:xmpp:carbons:2'>
                <forwarded xmlns='urn:xmpp:forward:0'>
                  <message type='chat' id='d1' from='10087@im.example.com/pc' to='10086@im.example.com/pc'>
                    <displayed xmlns='urn:xmpp:chat-markers:0' id='5b1e'/>
                  </message>
                </forwarded>
              </received>
            </message>
            """.trimIndent(),
        )

        assertThat(result.envelope).isEqualTo(MessageEnvelope.Carbon(sent = false, outerFrom = "10086@im.example.com"))
        assertThat(result.message.displayedId).isEqualTo("5b1e")
    }

    /** XEP-0184 的 received（回执）与 Carbons 的 received 同名不同命名空间，不能混淆 */
    @Test
    fun parse_receiptIsNotCarbon() {
        val result = MessageCodec.parse("<message id='r'><received xmlns='urn:xmpp:receipts' id='5b1e'/></message>")

        assertThat(result.envelope).isNull()
        assertThat(result.message.receivedId).isEqualTo("5b1e")
    }

    /** Carbons 包装中缺少 forwarded：当作普通消息处理（不崩溃） */
    @Test
    fun parse_brokenCarbon_treatedAsPlain() {
        val result = MessageCodec.parse("<message id='a'><sent xmlns='urn:xmpp:carbons:2'/></message>")

        assertThat(result.envelope).isNull()
        assertThat(result.message.id).isEqualTo("a")
    }

    /** §11.2 MAM 结果：stanza-id、queryid、forwarded 中的存档时间（优先于内层 delay） */
    @Test
    fun parse_mamResult() {
        val result = MessageCodec.parse(
            """
            <message xmlns='jabber:client' to='10086@im.example.com/android-1'>
              <result xmlns='urn:xmpp:mam:2' queryid='q1' id='s-100'>
                <forwarded xmlns='urn:xmpp:forward:0'>
                  <delay xmlns='urn:xmpp:delay' stamp='2026-10-13T01:00:00.000Z'/>
                  <message xmlns='jabber:client' type='chat' id='5b1e' from='10087@im.example.com/pc' to='10086@im.example.com'>
                    <body>历史消息</body>
                    <delay xmlns='urn:xmpp:delay' stamp='2026-10-13T00:59:00.000Z'/>
                    <request xmlns='urn:xmpp:receipts'/>
                  </message>
                </forwarded>
              </result>
            </message>
            """.trimIndent(),
        )

        assertThat(result.envelope).isEqualTo(MessageEnvelope.Mam("q1", "s-100", stamp1, null))
        assertThat(result.message.msgId).isEqualTo("5b1e")
        assertThat(result.message.body).isEqualTo("历史消息")
        assertThat(result.message.delayStamp).isEqualTo(stamp1)
        assertThat(result.message.requestReceipt).isTrue()
    }

    /** 群归档的 MAM 结果：外层 from 为群 JID，内层 from 为群内昵称 */
    @Test
    fun parse_mamGroupResult() {
        val result = MessageCodec.parse(
            """
            <message from='g1@conference.im.example.com' to='10086@im.example.com/android-1'>
              <result xmlns='urn:xmpp:mam:2' queryid='q2' id='s-7'>
                <forwarded xmlns='urn:xmpp:forward:0'>
                  <delay xmlns='urn:xmpp:delay' stamp='2026-10-13T01:00:00Z'/>
                  <message type='groupchat' id='g-1' from='g1@conference.im.example.com/10087'>
                    <body>群消息</body>
                  </message>
                </forwarded>
              </result>
            </message>
            """.trimIndent(),
        )

        val envelope = result.envelope as MessageEnvelope.Mam
        assertThat(envelope.outerFrom).isEqualTo("g1@conference.im.example.com")
        assertThat(envelope.stanzaId).isEqualTo("s-7")
        assertThat(envelope.stamp).isEqualTo(stamp1)
        assertThat(result.message.type).isEqualTo("groupchat")
    }

    /** MAM result 缺少 id：不是合法的补拉结果，当作普通消息 */
    @Test
    fun parse_mamWithoutId_treatedAsPlain() {
        val result = MessageCodec.parse(
            "<message id='outer'><result xmlns='urn:xmpp:mam:2' queryid='q1'>" +
                "<forwarded xmlns='urn:xmpp:forward:0'><message id='inner'/></forwarded></result></message>",
        )

        assertThat(result.envelope).isNull()
        assertThat(result.message.id).isEqualTo("outer")
    }

    @Test
    fun parse_notMessage_throws() {
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.parse("<iq type='get'/>") }
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.parse("<message") }
    }

    // ---------------------------------------------------------------- 时间

    @Test
    fun parseTime_formats() {
        assertThat(MessageCodec.parseTime("2026-10-13T01:00:00.000Z")).isEqualTo(stamp1)
        assertThat(MessageCodec.parseTime("2026-10-13T01:00:00Z")).isEqualTo(stamp1)
        assertThat(MessageCodec.parseTime("2026-10-13T09:00:00+08:00")).isEqualTo(stamp1)
        assertThat(MessageCodec.parseTime("2026-10-13T01:00:00.123456Z")).isEqualTo(stamp1 + 123)
        assertThat(MessageCodec.parseTime("昨天")).isNull()
    }

    @Test
    fun formatTime_utcMillis() {
        assertThat(MessageCodec.formatTime(stamp1 + 5)).isEqualTo("2026-10-13T01:00:00.005Z")
        assertThat(MessageCodec.parseTime(MessageCodec.formatTime(stamp1 + 5))).isEqualTo(stamp1 + 5)
    }

    // ---------------------------------------------------------------- 生成

    /** §6.1：单聊消息的结构与协议示例一致 */
    @Test
    fun chat_singleText_matchesProtocol() {
        val xml = MessageCodec.chat("10087@im.example.com", "5b1e", "你好", MessageContent.Text)

        assertThat(xml).isEqualTo(
            "<message type='chat' id='5b1e' to='10087@im.example.com'>" +
                "<body>你好</body>" +
                "<origin-id xmlns='urn:xmpp:sid:0' id='5b1e'/>" +
                "<content xmlns='urn:android666:im:msg:1' type='text'/>" +
                "<request xmlns='urn:xmpp:receipts'/>" +
                "<markable xmlns='urn:xmpp:chat-markers:0'/>" +
                "</message>",
        )
    }

    /** 群聊：type='groupchat'、不请求送达回执、带 @ */
    @Test
    fun chat_group_noReceiptRequestWithMentions() {
        val mentions = listOf(Mention(0, 3, "10086@im.example.com"), Mention(4, 8, "g1@conference.im.example.com"))
        val xml = MessageCodec.chat("g1@conference.im.example.com", "g", "@张三 @所有人 下午开会", MessageContent.Text, groupChat = true, mentions = mentions)

        assertThat(xml).startsWith("<message type='groupchat'")
        assertThat(xml).doesNotContain("urn:xmpp:receipts")
        val parsed = MessageCodec.parse(xml).message
        assertThat(parsed.mentions).isEqualTo(mentions)
        assertThat(parsed.markable).isTrue()
    }

    /** 正文中的特殊字符被转义，解析后还原 */
    @Test
    fun chat_escapesSpecialCharacters() {
        val body = "a < b && c > 'd' \"e\""
        val xml = MessageCodec.chat("10087@im.example.com", "id'1", body, MessageContent.Text)

        val parsed = MessageCodec.parse(xml).message
        assertThat(parsed.body).isEqualTo(body)
        assertThat(parsed.id).isEqualTo("id'1")
        assertThat(parsed.originId).isEqualTo("id'1")
    }

    /** 各种内容类型：生成 → 解析 往返一致 */
    @Test
    fun chat_roundTripAllContentTypes() {
        val file = FileInfo("https://h/a?x=1&y=2", "a&b.jpg", 10, "image/jpeg", sha256 = "s", width = 2, height = 3, duration = 4)
        val thumb = ThumbnailInfo("https://h/t.jpg", 1, 2, "image/jpeg")
        val contents = listOf(
            MessageContent.Text,
            MessageContent.Image(file, thumb, original = true),
            MessageContent.Image(file, null, original = false),
            MessageContent.Voice(file),
            MessageContent.Video(file, thumb),
            MessageContent.File(file),
            MessageContent.Card("10086@im.example.com", "张三"),
            MessageContent.Call("c1", "audio", "completed", 65),
            MessageContent.Call("c2", "video", "missed", null),
            MessageContent.Merged(
                "聊天记录",
                listOf(
                    ForwardedItem("m1", "10086@im.example.com", "hi", MessageContent.Text, stamp1),
                    ForwardedItem("m2", "10087@im.example.com", "[图片]", MessageContent.Image(file, thumb), null),
                ),
            ),
        )

        for (content in contents) {
            val xml = MessageCodec.chat("10087@im.example.com", "id", MessageCodec.fallbackBody(content, "hi"), content)
            assertThat(MessageCodec.parse(xml).message.content).isEqualTo(content)
        }
    }

    /** 引用回复往返 */
    @Test
    fun chat_withReply() {
        val xml = MessageCodec.chat("10087@im.example.com", "r", "好的", MessageContent.Text, reply = ReplyRef("10087@im.example.com", "5b1e"))

        assertThat(xml).contains("<reply xmlns='urn:xmpp:reply:0' to='10087@im.example.com' id='5b1e'/>")
        assertThat(MessageCodec.parse(xml).message.reply).isEqualTo(ReplyRef("10087@im.example.com", "5b1e"))
    }

    /** §8.1 送达回执 */
    @Test
    fun receipt_matchesProtocol() {
        assertThat(MessageCodec.receipt("10086@im.example.com/pc", "5b1e", "r-1")).isEqualTo(
            "<message to='10086@im.example.com/pc' id='r-1'>" +
                "<received xmlns='urn:xmpp:receipts' id='5b1e'/>" +
                "<no-store xmlns='urn:xmpp:hints'/></message>",
        )
    }

    /** §8.2 已读 */
    @Test
    fun displayed_matchesProtocol() {
        assertThat(MessageCodec.displayed("10086@im.example.com", "5b1e", "m-1")).isEqualTo(
            "<message type='chat' to='10086@im.example.com' id='m-1'>" +
                "<displayed xmlns='urn:xmpp:chat-markers:0' id='5b1e'/>" +
                "<store xmlns='urn:xmpp:hints'/></message>",
        )
    }

    /** §8.4 撤回 */
    @Test
    fun retract_matchesProtocol() {
        assertThat(MessageCodec.retract("10087@im.example.com", "5b1e", "张三撤回了一条消息", "x-1")).isEqualTo(
            "<message type='chat' to='10087@im.example.com' id='x-1'>" +
                "<body>张三撤回了一条消息</body>" +
                "<retract xmlns='urn:xmpp:message-retract:1' id='5b1e'/>" +
                "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:message-retract:1'/>" +
                "<store xmlns='urn:xmpp:hints'/></message>",
        )
        assertThat(MessageCodec.retract("g1@conference.im.example.com", "5b1e", "x", "x-2", groupChat = true))
            .startsWith("<message type='groupchat'")
    }

    /** §8.3 正在输入 */
    @Test
    fun chatState_composingAndInvalid() {
        assertThat(MessageCodec.chatState("10087@im.example.com", "composing", "c-1")).isEqualTo(
            "<message type='chat' to='10087@im.example.com' id='c-1'>" +
                "<composing xmlns='http://jabber.org/protocol/chatstates'/><no-store xmlns='urn:xmpp:hints'/></message>",
        )
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.chatState("a@b", "typing", "c-2") }
    }

    // ---------------------------------------------------------------- 降级文本

    /** §7 表格中的降级文本 */
    @Test
    fun fallbackBody_allTypes() {
        val file = FileInfo("u", "季度报告.pdf", 1, "application/pdf")
        assertThat(MessageCodec.fallbackBody(MessageContent.Text, "原文")).isEqualTo("原文")
        assertThat(MessageCodec.fallbackBody(MessageContent.Image(file, null))).isEqualTo("[图片]")
        assertThat(MessageCodec.fallbackBody(MessageContent.Voice(file))).isEqualTo("[语音]")
        assertThat(MessageCodec.fallbackBody(MessageContent.Video(file, null))).isEqualTo("[视频]")
        assertThat(MessageCodec.fallbackBody(MessageContent.File(file))).isEqualTo("[文件] 季度报告.pdf")
        assertThat(MessageCodec.fallbackBody(MessageContent.Card("j", "张三"))).isEqualTo("[名片] 张三")
        assertThat(MessageCodec.fallbackBody(MessageContent.Merged("张三和李四的聊天记录", emptyList())))
            .isEqualTo("[聊天记录] 张三和李四的聊天记录")
        assertThat(MessageCodec.fallbackBody(MessageContent.Call("c", "video", "completed", 205))).isEqualTo("[视频通话] 通话时长 03:25")
        assertThat(MessageCodec.fallbackBody(MessageContent.Call("c", "audio", "rejected"))).isEqualTo("[语音通话] 对方已拒绝")
    }

    @Test
    fun callResultText_allResults() {
        fun text(result: String, duration: Int? = null) = MessageCodec.callResultText(MessageContent.Call("c", "audio", result, duration))
        assertThat(text("completed", 5)).isEqualTo("通话时长 00:05")
        assertThat(text("completed")).isEqualTo("通话时长 00:00")
        assertThat(text("canceled")).isEqualTo("已取消")
        assertThat(text("rejected")).isEqualTo("对方已拒绝")
        assertThat(text("missed")).isEqualTo("无人接听")
        assertThat(text("busy")).isEqualTo("对方忙")
        assertThat(text("failed")).isEqualTo("连接失败")
    }

    // ---------------------------------------------------------------- @ 码点换算

    /** §8.6：begin / end 按 Unicode 码点计数，emoji 在 UTF-16 中占 2 个 char */
    @Test
    fun mention_codePointConversion() {
        val text = "😀@张三 你好"
        // UTF-16：😀 占下标 0~1，@张三 为 [2, 5)
        val mention = Mention.fromUtf16(text, 2, 5, "10086@im.example.com")

        assertThat(mention).isEqualTo(Mention(1, 4, "10086@im.example.com"))
        assertThat(mention.utf16Range(text)).isEqualTo(2 until 5)
        assertThat(text.substring(mention.utf16Range(text)!!)).isEqualTo("@张三")
    }

    /** 超出正文范围（对方发来的数据不可信）：返回 null */
    @Test
    fun mention_outOfRange_null() {
        assertThat(Mention(0, 10, "j").utf16Range("abc")).isNull()
        assertThat(Mention(2, 1, "j").utf16Range("abc")).isNull()
        assertThat(Mention(-1, 1, "j").utf16Range("abc")).isNull()
    }

    // ---------------------------------------------------------------- JSON（消息表 contentJson）

    /** 内容用 kotlinx.serialization 保存到数据库，往返一致；类型名即协议 type */
    @Test
    fun contentJson_roundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        val file = FileInfo("u", "a.jpg", 1, "image/jpeg", width = 1, height = 1)
        val contents: List<MessageContent> = listOf(
            MessageContent.Text,
            MessageContent.Image(file, ThumbnailInfo("t", 1, 1, "image/jpeg"), original = true),
            MessageContent.Card("j", "张三"),
            MessageContent.Merged("t", listOf(ForwardedItem("m", "f", "b", MessageContent.Voice(file), stamp1))),
            MessageContent.Call("c", "audio", "missed"),
            MessageContent.Unknown("custom"),
        )

        for (content in contents) {
            val text = json.encodeToString(MessageContent.serializer(), content)
            assertThat(json.decodeFromString(MessageContent.serializer(), text)).isEqualTo(content)
        }
        assertThat(json.encodeToString(MessageContent.serializer(), MessageContent.Card("j", "张三")))
            .isEqualTo("""{"type":"card","jid":"j","name":"张三"}""")
    }

    /**
     * 解析单独的 content 元素。
     *
     * @param xml content 元素的 XML
     * @return 内容
     */
    private fun parseContent(xml: String): MessageContent =
        MessageCodec.parseContent(cn.android666.im.core.xmpp.iq.XmlNode.parse(xml.trimIndent()))
}
