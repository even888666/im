package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.IqType
import org.jivesoftware.smack.packet.IQ

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
