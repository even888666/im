package cn.android666.im.core.xmpp.iq

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 与 Smack 无关的简单 XML 元素树，用于解析协议规范中的自定义元素（Android工程架构.md §9.3：
 * 每个报文示例都要有解析测试，与 Smack 解耦后可以直接用示例 XML 字符串测试）。
 *
 * 基于 JDK / Android 都自带的 DOM 解析器（javax.xml.parsers），支持命名空间。
 *
 * @property name 元素名（不含前缀）
 * @property namespace 命名空间，没有时为空字符串
 * @property attributes 属性（不含 xmlns 声明）
 * @property children 子元素
 * @property text 元素直接包含的文本（不含子元素中的文本），已去掉首尾空白
 */
data class XmlNode(
    val name: String,
    val namespace: String,
    val attributes: Map<String, String>,
    val children: List<XmlNode>,
    val text: String,
) {

    /**
     * 读取属性。
     *
     * @param name 属性名
     * @return 属性值；不存在时返回 null
     */
    fun attr(name: String): String? = attributes[name]

    /**
     * 读取布尔属性（`true` / `1` 为真，协议规范使用 `true`）。
     *
     * @param name 属性名
     * @return 属性值；不存在时返回 false
     */
    fun boolAttr(name: String): Boolean = attributes[name].let { it == "true" || it == "1" }

    /**
     * 查找第一个指定名称的子元素。
     *
     * @param name 元素名
     * @param namespace 命名空间；为 null 时不限
     * @return 子元素；不存在时返回 null
     */
    fun child(name: String, namespace: String? = null): XmlNode? =
        children.firstOrNull { it.name == name && (namespace == null || it.namespace == namespace) }

    /**
     * 查找所有指定名称的子元素。
     *
     * @param name 元素名
     * @return 子元素列表
     */
    fun childrenNamed(name: String): List<XmlNode> = children.filter { it.name == name }

    companion object {

        /**
         * 解析 XML 字符串（根元素可以没有命名空间声明，此时 namespace 为空字符串）。
         *
         * 安全：禁用 DOCTYPE 和外部实体，防止 XXE 攻击（服务器返回的内容不完全可信）。
         *
         * @param xml XML 字符串
         * @return 根元素
         * @throws IllegalArgumentException XML 格式错误
         */
        fun parse(xml: String): XmlNode {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val document = try {
                factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException("XML 格式错误：${e.message}", e)
            }
            return fromElement(document.documentElement)
        }

        /**
         * DOM 元素 → [XmlNode]。
         *
         * @param element DOM 元素
         * @return 元素树
         */
        private fun fromElement(element: Element): XmlNode {
            val attributes = buildMap {
                val attrs = element.attributes
                for (i in 0 until attrs.length) {
                    val attr = attrs.item(i)
                    // 跳过命名空间声明 xmlns / xmlns:xx
                    if (attr.nodeName == "xmlns" || attr.nodeName.startsWith("xmlns:")) continue
                    put(attr.localName ?: attr.nodeName, attr.nodeValue)
                }
            }
            val children = mutableListOf<XmlNode>()
            val text = StringBuilder()
            val nodes = element.childNodes
            for (i in 0 until nodes.length) {
                when (val node = nodes.item(i)) {
                    is Element -> children += fromElement(node)
                    else -> if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                        text.append(node.nodeValue)
                    }
                }
            }
            return XmlNode(
                name = element.localName ?: element.nodeName,
                namespace = element.namespaceURI.orEmpty(),
                attributes = attributes,
                children = children,
                text = text.toString().trim(),
            )
        }
    }
}

/** XML 转义工具：拼接报文时，属性值和文本都必须转义，防止注入 */
object Xml {

    /**
     * 转义文本或属性值。
     *
     * @param value 原始内容
     * @return 转义后的内容（同时适用于单引号 / 双引号属性）
     */
    fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
