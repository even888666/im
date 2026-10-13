package cn.android666.im.core.xmpp.extension.org

import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.RsmResult
import cn.android666.im.core.xmpp.iq.XmlNode
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** 组织架构 IQ 测试：请求与结果都使用协议规范 §4 的示例 */
class OrgRequestsTest {

    /** §4.2 请求 */
    @Test
    fun deptChildren_request() {
        val request = DeptChildrenRequest("d1001", max = 200)
        assertThat(request.type).isEqualTo(IqType.GET)
        assertThat(request.to).isNull()
        assertThat(request.toChildXml()).isEqualTo(
            "<dept-children xmlns='urn:android666:im:org:1' deptId='d1001'>" +
                "<set xmlns='http://jabber.org/protocol/rsm'><max>200</max></set></dept-children>",
        )
        assertThat(DeptChildrenRequest("", max = 10, after = "9").toChildXml()).isEqualTo(
            "<dept-children xmlns='urn:android666:im:org:1' deptId=''>" +
                "<set xmlns='http://jabber.org/protocol/rsm'><max>10</max><after>9</after></set></dept-children>",
        )
    }

    /** §4.2 结果示例 */
    @Test
    fun deptChildren_parseProtocolResult() {
        val result = XmlNode.parse(
            """
            <dept-children xmlns='urn:android666:im:org:1' deptId='d1001'>
              <department id='d1002' parentId='d1001' name='Android组' sort='10' memberCount='25'/>
              <department id='d1003' parentId='d1001' name='iOS组' sort='20' memberCount='18'/>
              <employee jid='10086@im.example.com' empNo='10086' name='张三' pinyin='zhangsan'
                        initials='zs' deptId='d1001' title='部门经理' mobile='13800000000'
                        phone='' email='zhangsan@example.com' sort='0' avatarHash='a1b2c3'/>
              <set xmlns='http://jabber.org/protocol/rsm'>
                <first>0</first><last>0</last><count>1</count>
              </set>
            </dept-children>
            """.trimIndent(),
        )
        val children = DeptChildrenRequest("d1001").parseResult(result)
        assertThat(children.departments).containsExactly(
            OrgDepartment("d1002", "d1001", "Android组", 10, 25),
            OrgDepartment("d1003", "d1001", "iOS组", 20, 18),
        ).inOrder()
        assertThat(children.employees).containsExactly(
            OrgEmployee(
                jid = "10086@im.example.com", empNo = "10086", name = "张三", pinyin = "zhangsan", initials = "zs",
                deptId = "d1001", title = "部门经理", mobile = "13800000000", phone = "", email = "zhangsan@example.com",
                sort = 0, avatarHash = "a1b2c3",
            ),
        )
        assertThat(children.rsm).isEqualTo(RsmResult(first = "0", last = "0", count = 1))
    }

    /** §4.3：请求与结果，op='delete' 为已离职 */
    @Test
    fun employees_requestAndResult() {
        val request = EmployeesRequest(listOf("10086@im.example.com", "10087@im.example.com"))
        assertThat(request.toChildXml()).isEqualTo(
            "<employees xmlns='urn:android666:im:org:1'>" +
                "<item jid='10086@im.example.com'/><item jid='10087@im.example.com'/></employees>",
        )
        val result = request.parseResult(
            XmlNode.parse(
                """
                <employees xmlns='urn:android666:im:org:1'>
                  <employee jid='10086@im.example.com' empNo='10086' name='张三'/>
                  <employee jid='10087@im.example.com' op='delete'/>
                </employees>
                """.trimIndent(),
            ),
        )
        assertThat(result.employees.map { it.name }).containsExactly("张三")
        assertThat(result.deletedJids).containsExactly("10087@im.example.com")
    }

    @Test
    fun employees_tooMany_rejected() {
        assertThrows(IllegalArgumentException::class.java) { EmployeesRequest(List(101) { "$it@im.example.com" }) }
    }

    /** §4.4 请求与结果示例 */
    @Test
    fun sync_requestAndProtocolResult() {
        assertThat(OrgSyncRequest(1520, max = 500).toChildXml()).isEqualTo(
            "<sync xmlns='urn:android666:im:org:1' version='1520'>" +
                "<set xmlns='http://jabber.org/protocol/rsm'><max>500</max></set></sync>",
        )
        val page = OrgSyncRequest(1520).parseResult(
            XmlNode.parse(
                """
                <sync xmlns='urn:android666:im:org:1' version='1536' full='false'>
                  <department id='d1004' parentId='d1001' name='测试组' sort='30' memberCount='0' op='upsert'/>
                  <employee jid='10099@im.example.com' empNo='10099' name='赵六' op='upsert'/>
                  <employee jid='10050@im.example.com' op='delete'/>
                  <department id='d1005' op='delete'/>
                  <set xmlns='http://jabber.org/protocol/rsm'>
                    <first>c-0</first><last>c-2</last><count>3</count>
                  </set>
                </sync>
                """.trimIndent(),
            ),
        )
        assertThat(page.version).isEqualTo(1536)
        assertThat(page.full).isFalse()
        assertThat(page.entries).hasSize(4)
        assertThat(page.entries[0]).isEqualTo(OrgSyncEntry.DepartmentUpsert(OrgDepartment("d1004", "d1001", "测试组", 30, 0)))
        assertThat((page.entries[1] as OrgSyncEntry.EmployeeUpsert).employee.name).isEqualTo("赵六")
        assertThat(page.entries[2]).isEqualTo(OrgSyncEntry.EmployeeDelete("10050@im.example.com"))
        assertThat(page.entries[3]).isEqualTo(OrgSyncEntry.DepartmentDelete("d1005"))
        assertThat(page.rsm).isEqualTo(RsmResult("c-0", "c-2", 3))
    }

    /** 最后一页为空：没有 first / last */
    @Test
    fun sync_emptyPage() {
        val page = OrgSyncRequest(0, after = "e:10010").parseResult(
            XmlNode.parse(
                "<sync xmlns='urn:android666:im:org:1' version='40' full='true'>" +
                    "<set xmlns='http://jabber.org/protocol/rsm'><count>30</count></set></sync>",
            ),
        )
        assertThat(page.full).isTrue()
        assertThat(page.entries).isEmpty()
        assertThat(page.rsm).isEqualTo(RsmResult(null, null, 30))
    }

    @Test
    fun sync_missingVersion_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OrgSyncRequest(0).parseResult(XmlNode.parse("<sync xmlns='urn:android666:im:org:1'/>"))
        }
    }

    /** 不认识的属性和子元素忽略（协议规范 §1.5） */
    @Test
    fun unknownAttributesAndElementsIgnored() {
        val page = OrgSyncRequest(0).parseResult(
            XmlNode.parse(
                "<sync xmlns='urn:android666:im:org:1' version='1' full='true' extra='x'>" +
                    "<employee jid='1@im.example.com' name='a' newAttr='y'/><future/></sync>",
            ),
        )
        assertThat(page.entries).hasSize(1)
    }

    /** §4.5 请求 */
    @Test
    fun search_request() {
        assertThat(SearchEmployeesRequest("zs", max = 20).toChildXml()).isEqualTo(
            "<search xmlns='urn:android666:im:org:1' keyword='zs'>" +
                "<set xmlns='http://jabber.org/protocol/rsm'><max>20</max></set></search>",
        )
        val result = SearchEmployeesRequest("zs").parseResult(
            XmlNode.parse("<search xmlns='urn:android666:im:org:1' keyword='zs'><employee jid='1@im.example.com' name='张三'/></search>"),
        )
        assertThat(result.single().empNo).isEqualTo("1")
    }

    @Test
    fun wrongNamespace_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchEmployeesRequest("zs").parseResult(XmlNode.parse("<search xmlns='urn:other'/>"))
        }
    }
}
