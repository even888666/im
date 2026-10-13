package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.IqType
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.IqData
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.IqProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.jxmpp.JxmppContext
import org.jivesoftware.smack.packet.UnparsedIQ

/**
 * 把与 Smack 无关的 [IqRequest] 包装成 Smack 的 IQ。子元素的属性由 Smack 转义，内部 XML 原样写入
 * （IqRequest 的实现负责转义）。
 *
 * @property request 要发送的请求
 */
internal class RawIq(private val request: IqRequest<*>) : IQ(request.elementName, request.namespace) {

    init {
        type = when (request.type) {
            IqType.GET -> Type.get
            IqType.SET -> Type.set
        }
    }

    /**
     * 写入子元素的属性与内容。
     *
     * @param xml Smack 已写好 `<elementName xmlns='namespace'` 的构造器
     * @return 构造器
     */
    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
        request.attributes.forEach { (key, value) -> xml.attribute(key, value) }
        val children = request.childrenXml()
        if (children.isEmpty()) {
            xml.setEmptyElement()
        } else {
            xml.rightAngleBracket()
            xml.append(children)
        }
        return xml
    }
}

/**
 * 自定义 IQ 的结果：保存完整的子元素 XML（包括嵌套元素的命名空间），交给 XmlNode 解析。
 *
 * @param element 子元素名
 * @param namespace 子元素命名空间
 * @property xml 子元素的完整 XML
 */
internal class RawResultIq(element: String, namespace: String, val xml: String) : IQ(element, namespace) {

    /**
     * 只用于接收，不会发送；Smack 输出日志时调用，原样输出子元素内容。
     *
     * @param xml 构造器
     * @return 构造器
     */
    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder =
        xml.apply { setEmptyElement() }
}

/**
 * 把自定义命名空间的 IQ 解析为 [RawResultIq]：用 `parseElement(parser, fullNamespaces = true)` 保留嵌套元素的命名空间。
 *
 * 不能直接用 Smack 默认的 UnparsedIQ：它的 content 会丢掉嵌套元素的 xmlns（例如 RSM 的
 * `<set xmlns='http://jabber.org/protocol/rsm'>` 变成 `<set>`），见踩坑 4.4.13。
 */
internal object RawResultIqProvider : IqProvider<RawResultIq>() {

    /**
     * 解析子元素。
     *
     * @param parser 位于子元素开始标签的解析器
     * @param initialDepth 子元素的深度
     * @param iqData IQ 的基本信息
     * @param xmlEnvironment XML 环境
     * @param jxmppContext JID 处理上下文
     * @return 结果
     */
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        iqData: IqData,
        xmlEnvironment: XmlEnvironment?,
        jxmppContext: JxmppContext?,
    ): RawResultIq {
        val element = parser.name
        val namespace = parser.namespace
        val xml = PacketParserUtils.parseElement(parser, true).toString()
        return RawResultIq(element, namespace, xml)
    }

    /**
     * 为请求的元素注册本解析器。Smack 已有解析器的元素（如 XEP-0077 的 query）不覆盖，以免影响 Smack 自带的功能。
     *
     * @param request 即将发送的请求
     */
    fun registerFor(request: IqRequest<*>) {
        if (ProviderManager.getIQProvider(request.elementName, request.namespace) == null) {
            ProviderManager.addIQProvider(request.elementName, request.namespace, this)
        }
    }
}

/**
 * 取出 IQ 结果的子元素 XML，交给 [cn.android666.im.core.xmpp.iq.XmlNode] 解析。
 *
 * - [RawResultIq]：完整的子元素 XML；
 * - [UnparsedIQ]：原始子元素在 content 中（嵌套元素的命名空间可能丢失，正常情况下已由 [RawResultIqProvider] 处理）；
 *   不能用 childElementXML：它会把原始内容转义后包进一个没有属性的新元素，属性全部丢失（踩坑 4.4.13）；
 * - 其他（Smack 自带解析器的结果）：childElementXML。
 *
 * @param response 结果 IQ
 * @return 子元素 XML；空结果（`<iq type='result'/>`）时为 null
 */
internal fun resultChildXml(response: IQ): String? = when {
    response is RawResultIq -> response.xml
    response is UnparsedIQ -> response.content.toString().takeIf { it.isNotBlank() }
    response.childElementName == null -> null
    else -> response.childElementXML.toString()
}
