package cn.android666.im.core.xmpp.iq

/** IQ 类型 */
enum class IqType {
    /** 查询 */
    GET,

    /** 设置 */
    SET,
}

/**
 * 自定义 IQ 请求（Android工程架构.md §4.2 中的 `sendIq`）。协议规范中的每个 IQ 写成一个子类，
 * 只描述请求内容和结果解析，与 Smack 无关，便于用规范中的示例做单元测试。
 *
 * 请求的子元素：`<elementName xmlns='namespace' 属性…>childrenXml</elementName>`。
 *
 * @param T 结果类型
 * @property type IQ 类型
 * @property elementName 子元素名
 * @property namespace 子元素命名空间
 * @property to 发送目标；null 表示服务器域名（自研插件的 IQ 都发往服务器域名，协议规范 §1.1）
 */
abstract class IqRequest<T>(
    val type: IqType,
    val elementName: String,
    val namespace: String,
    val to: String? = null,
) {

    /**
     * 是否发给本人账号（不带 to 属性，由服务器代表本人的账号处理，如查询本人的 MAM 归档）。
     * 为 true 时忽略 [to]。
     */
    open val toOwnAccount: Boolean get() = false

    /** 子元素的属性（值会自动转义） */
    open val attributes: Map<String, String> = emptyMap()

    /**
     * 子元素内部的 XML，调用方负责转义（使用 [Xml.escape]）。
     *
     * @return 内部 XML；没有内容时返回空字符串（生成空元素）
     */
    open fun childrenXml(): String = ""

    /**
     * 解析结果。
     *
     * @param result 结果 IQ 的子元素；服务器返回空结果（`<iq type='result'/>`）时为 null
     * @return 结果
     * @throws IllegalArgumentException 结果格式不符合协议
     */
    abstract fun parseResult(result: XmlNode?): T

    /**
     * 生成完整的子元素 XML（不含外层 iq），用于日志和测试。
     *
     * @return 子元素 XML
     */
    fun toChildXml(): String = buildString {
        append('<').append(elementName).append(" xmlns='").append(Xml.escape(namespace)).append('\'')
        attributes.forEach { (key, value) -> append(' ').append(key).append("='").append(Xml.escape(value)).append('\'') }
        val children = childrenXml()
        if (children.isEmpty()) append("/>") else append('>').append(children).append("</").append(elementName).append('>')
    }
}
