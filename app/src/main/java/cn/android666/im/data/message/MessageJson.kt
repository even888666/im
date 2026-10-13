package cn.android666.im.data.message

import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.extension.msg.Mention
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 消息表中 JSON 列（contentJson、mentionsJson）的读写。
 * 读取失败（例如旧版本写入、升级后格式变化）时不抛异常，按"未知内容 / 没有 @"处理并记录日志。
 */
object MessageJson {

    private val log = Logs.file("MessageJson")

    /** 忽略未知字段：新版本增加的字段不影响旧版本读取 */
    private val json = Json { ignoreUnknownKeys = true }

    /** @ 列表的序列化器 */
    private val mentionsSerializer = ListSerializer(Mention.serializer())

    /**
     * 内容 → contentJson。文本消息不保存内容（正文在 body 中）。
     *
     * @param content 内容
     * @return JSON；文本时为 null
     */
    fun encodeContent(content: MessageContent): String? =
        if (content is MessageContent.Text) null else json.encodeToString(MessageContent.serializer(), content)

    /**
     * contentJson → 内容。
     *
     * @param type 消息表中的类型（JSON 为空或损坏时的兜底）
     * @param contentJson JSON；文本消息为 null
     * @return 内容
     */
    fun decodeContent(type: String, contentJson: String?): MessageContent {
        if (contentJson == null) return if (type == MessageContent.Text.type) MessageContent.Text else MessageContent.Unknown(type)
        return try {
            json.decodeFromString(MessageContent.serializer(), contentJson)
        } catch (e: SerializationException) {
            log.warn("消息内容无法解析，按未知类型处理：type={}", type, e)
            MessageContent.Unknown(type)
        } catch (e: IllegalArgumentException) {
            log.warn("消息内容无法解析，按未知类型处理：type={}", type, e)
            MessageContent.Unknown(type)
        }
    }

    /**
     * @ 列表 → mentionsJson。
     *
     * @param mentions @ 列表
     * @return JSON；为空时为 null
     */
    fun encodeMentions(mentions: List<Mention>): String? =
        if (mentions.isEmpty()) null else json.encodeToString(mentionsSerializer, mentions)

    /**
     * mentionsJson → @ 列表。
     *
     * @param mentionsJson JSON；为 null 时没有 @
     * @return @ 列表；损坏时为空
     */
    fun decodeMentions(mentionsJson: String?): List<Mention> {
        if (mentionsJson == null) return emptyList()
        return try {
            json.decodeFromString(mentionsSerializer, mentionsJson)
        } catch (e: SerializationException) {
            log.warn("@ 信息无法解析，忽略", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            log.warn("@ 信息无法解析，忽略", e)
            emptyList()
        }
    }
}
