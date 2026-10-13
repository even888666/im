package cn.android666.im.core.xmpp.iq

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** [XmlNode] 与 [Xml] 测试 */
class XmlNodeTest {

    /** 协议规范 §4.2 的结果示例：命名空间、属性、子元素、嵌套命名空间 */
    @Test
    fun parse_protocolExample() {
        val node = XmlNode.parse(
            """
            <dept-children xmlns='urn:android666:im:org:1' deptId='d1001'>
              <department id='d1002' parentId='d1001' name='Android组' sort='10' memberCount='25'/>
              <employee jid='10086@im.example.com' empNo='10086' name='张三' pinyin='zhangsan'/>
              <set xmlns='http://jabber.org/protocol/rsm'><first>0</first><count>1</count></set>
            </dept-children>
            """.trimIndent(),
        )

        assertThat(node.name).isEqualTo("dept-children")
        assertThat(node.namespace).isEqualTo("urn:android666:im:org:1")
        assertThat(node.attributes).containsExactly("deptId", "d1001")
        assertThat(node.childrenNamed("department").single().attr("name")).isEqualTo("Android组")
        // 子元素继承父元素的默认命名空间
        assertThat(node.child("employee")!!.namespace).isEqualTo("urn:android666:im:org:1")
        val rsm = node.child("set", "http://jabber.org/protocol/rsm")!!
        assertThat(rsm.child("count")!!.text).isEqualTo("1")
        assertThat(node.child("set", "urn:other")).isNull()
    }

    @Test
    fun parse_entitiesAndText() {
        val node = XmlNode.parse("<body>  a &amp; b &lt;c&gt; &apos;d&apos;  </body>")
        assertThat(node.text).isEqualTo("a & b <c> 'd'")
        assertThat(node.namespace).isEmpty()
    }

    @Test
    fun boolAttr() {
        val node = XmlNode.parse("<a x='true' y='1' z='false'/>")
        assertThat(node.boolAttr("x")).isTrue()
        assertThat(node.boolAttr("y")).isTrue()
        assertThat(node.boolAttr("z")).isFalse()
        assertThat(node.boolAttr("missing")).isFalse()
    }

    /** 未知属性和子元素不影响解析（协议规范 §1.5） */
    @Test
    fun parse_unknownContentIgnored() {
        val node = XmlNode.parse("<account xmlns='urn:android666:im:account:1' mustChangePassword='true' future='x'><new/></account>")
        assertThat(node.boolAttr("mustChangePassword")).isTrue()
    }

    @Test
    fun parse_malformed_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) { XmlNode.parse("<a><b></a>") }
    }

    /** 安全：拒绝 DOCTYPE（防止 XXE 外部实体读取本地文件） */
    @Test
    fun parse_doctype_rejected() {
        val xxe = "<?xml version='1.0'?><!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>"
        assertThrows(IllegalArgumentException::class.java) { XmlNode.parse(xxe) }
    }

    @Test
    fun escape_allSpecialChars() {
        assertThat(Xml.escape("a&b<c>d\"e'f")).isEqualTo("a&amp;b&lt;c&gt;d&quot;e&apos;f")
        assertThat(Xml.escape("普通文本")).isEqualTo("普通文本")
    }
}
