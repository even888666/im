package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.iq.XmlNode
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.stringprep.XmppStringprepException

/**
 * message 报文在"与 Smack 无关的 XML 字符串"和 Smack [Message] 之间的转换。
 *
 * 发送方向不用 Smack 的扩展解析器（其中一些类型化的扩展元素在重新序列化时会丢掉不认识的属性，
 * 例如 XEP-0428 fallback 的 for），而是把每个子元素原样转换为 [StandardExtensionElement]，保证无损。
 */
internal object SmackMessages {

    /**
     * XML → Smack 消息（发送前）。
     *
     * @param xml message 报文，必须带 id
     * @return Smack 消息
     * @throws IllegalArgumentException 不是 message、没有 id、JID 或 type 非法
     */
    fun build(xml: String): Message {
        val node = XmlNode.parse(xml)
        require(node.name == "message") { "不是 message 元素：${node.name}" }
        val id = requireNotNull(node.attr("id")) { "message 必须带 id" }
        val builder = StanzaBuilder.buildMessage(id)
        node.attr("type")?.let { builder.ofType(Message.Type.fromString(it)) }
        try {
            node.attr("to")?.let { builder.to(JidCreate.from(it)) }
        } catch (e: XmppStringprepException) {
            throw IllegalArgumentException("接收方 JID 非法：${node.attr("to")}", e)
        }
        for (child in node.children) {
            if (child.name == "body" && (child.namespace.isEmpty() || child.namespace == CLIENT_NS)) {
                builder.setBody(child.rawText)
            } else {
                builder.addExtension(toExtension(child))
            }
        }
        return builder.build()
    }

    /**
     * Smack 消息 → XML（收到后交给 MessageCodec 解析）。
     *
     * @param message Smack 消息
     * @return message 报文
     */
    fun toXml(message: Message): String = message.toXML().toString()

    /**
     * 递归地把 [XmlNode] 转为 [StandardExtensionElement]（保留全部属性、文本、子元素和命名空间）。
     *
     * @param node 元素
     * @return 扩展元素
     */
    private fun toExtension(node: XmlNode): StandardExtensionElement {
        val builder = StandardExtensionElement.builder(node.name, node.namespace)
        node.attributes.forEach { (key, value) -> builder.addAttribute(key, value) }
        if (node.children.isEmpty()) {
            if (node.rawText.isNotEmpty()) builder.setText(node.rawText)
        } else {
            node.children.forEach { builder.addElement(toExtension(it)) }
        }
        return builder.build()
    }

    /** 客户端 stanza 的默认命名空间 */
    private const val CLIENT_NS = "jabber:client"
}
