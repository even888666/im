package cn.android666.im.core.xmpp.extension.org

import cn.android666.im.core.xmpp.iq.IqRequest
import cn.android666.im.core.xmpp.iq.IqType
import cn.android666.im.core.xmpp.iq.Rsm
import cn.android666.im.core.xmpp.iq.RsmResult
import cn.android666.im.core.xmpp.iq.Xml
import cn.android666.im.core.xmpp.iq.XmlNode

/** 组织架构命名空间（协议规范 §4） */
const val ORG_NAMESPACE = "urn:android666:im:org:1"

/**
 * 检查结果元素的名称和命名空间。
 *
 * @param result 结果子元素
 * @param element 期望的元素名
 * @return 结果子元素
 * @throws IllegalArgumentException 格式不符
 */
private fun requireOrgResult(result: XmlNode?, element: String): XmlNode {
    require(result != null && result.name == element && result.namespace == ORG_NAMESPACE) { "组织架构结果格式错误：$result" }
    return result
}

/**
 * 部门下级查询结果（协议规范 §4.2）。
 *
 * @property departments 下级部门（全部）
 * @property employees 本页的直属员工
 * @property rsm 员工分页信息（游标为员工下标）
 */
data class DeptChildren(val departments: List<OrgDepartment>, val employees: List<OrgEmployee>, val rsm: RsmResult?)

/**
 * 获取部门下级（协议规范 §4.2）：下级部门全部返回，直属员工按 RSM 分页。
 *
 * @property deptId 部门 ID，空字符串表示查询顶级部门
 * @property max 员工每页条数
 * @property after 上一页最后一个员工的游标，第一页为 null
 */
class DeptChildrenRequest(
    val deptId: String,
    val max: Int = 200,
    val after: String? = null,
) : IqRequest<DeptChildren>(IqType.GET, "dept-children", ORG_NAMESPACE) {

    override val attributes: Map<String, String> = mapOf("deptId" to deptId)

    override fun childrenXml(): String = Rsm.requestXml(max, after)

    /**
     * 解析结果。
     *
     * @param result 结果子元素
     * @return 下级部门与本页员工
     */
    override fun parseResult(result: XmlNode?): DeptChildren {
        val node = requireOrgResult(result, elementName)
        return DeptChildren(
            departments = node.childrenNamed("department").map(OrgXml::department),
            employees = node.childrenNamed("employee").map(OrgXml::employee),
            rsm = Rsm.parse(node),
        )
    }
}

/**
 * 批量员工详情的结果（协议规范 §4.3）。
 *
 * @property employees 查到的员工
 * @property deletedJids 不存在或已离职的员工（客户端显示为"已离职"）
 */
data class EmployeesResult(val employees: List<OrgEmployee>, val deletedJids: List<String>)

/**
 * 批量获取员工详情（协议规范 §4.3），每次最多 100 个。
 *
 * @property jids 员工 bare JID 列表
 */
class EmployeesRequest(val jids: List<String>) :
    IqRequest<EmployeesResult>(IqType.GET, "employees", ORG_NAMESPACE) {

    init {
        require(jids.size <= MAX_ITEMS) { "每次最多查询 $MAX_ITEMS 个员工：${jids.size}" }
    }

    override fun childrenXml(): String = jids.joinToString("") { "<item jid='${Xml.escape(it)}'/>" }

    /**
     * 解析结果：op='delete' 的为已离职。
     *
     * @param result 结果子元素
     * @return 员工与已离职的 JID
     */
    override fun parseResult(result: XmlNode?): EmployeesResult {
        val nodes = requireOrgResult(result, elementName).childrenNamed("employee")
        val (deleted, found) = nodes.partition { it.attr("op") == OrgXml.OP_DELETE }
        return EmployeesResult(found.map(OrgXml::employee), deleted.mapNotNull { it.attr("jid") })
    }

    companion object {
        /** 每次查询的上限（协议规范 §1.6） */
        const val MAX_ITEMS = 100
    }
}

/**
 * 组织架构同步的一页（协议规范 §4.4）。
 *
 * @property version 同步完成后应保存的版本号
 * @property full 是否为全量数据（客户端必须先清空本地组织架构缓存再写入）
 * @property entries 本页的变更
 * @property rsm 分页信息
 */
data class OrgSyncPage(val version: Long, val full: Boolean, val entries: List<OrgSyncEntry>, val rsm: RsmResult?)

/**
 * 组织架构同步（协议规范 §4.4）：携带本地版本号，服务器返回增量或全量（full='true'）。
 * 用 RSM after 继续拉取下一页，直到返回条数为 0。
 *
 * @property version 本地版本号，首次为 0
 * @property max 每页条数
 * @property after 上一页最后一项的游标，第一页为 null
 */
class OrgSyncRequest(
    val version: Long,
    val max: Int = 500,
    val after: String? = null,
) : IqRequest<OrgSyncPage>(IqType.GET, "sync", ORG_NAMESPACE) {

    override val attributes: Map<String, String> = mapOf("version" to version.toString())

    override fun childrenXml(): String = Rsm.requestXml(max, after)

    /**
     * 解析结果，部门和员工按出现顺序转为变更项。
     *
     * @param result 结果子元素
     * @return 本页结果
     */
    override fun parseResult(result: XmlNode?): OrgSyncPage {
        val node = requireOrgResult(result, elementName)
        val entries = node.children.mapNotNull { child ->
            val delete = child.attr("op") == OrgXml.OP_DELETE
            when (child.name) {
                "department" -> if (delete) {
                    OrgSyncEntry.DepartmentDelete(requireNotNull(child.attr("id")) { "部门缺少 id：$child" })
                } else {
                    OrgSyncEntry.DepartmentUpsert(OrgXml.department(child))
                }
                "employee" -> if (delete) {
                    OrgSyncEntry.EmployeeDelete(requireNotNull(child.attr("jid")) { "员工缺少 jid：$child" })
                } else {
                    OrgSyncEntry.EmployeeUpsert(OrgXml.employee(child))
                }
                // set 等其他元素
                else -> null
            }
        }
        return OrgSyncPage(
            version = requireNotNull(node.attr("version")?.toLongOrNull()) { "同步结果缺少 version：$node" },
            full = node.boolAttr("full"),
            entries = entries,
            rsm = Rsm.parse(node),
        )
    }
}

/**
 * 搜索同事（协议规范 §4.5，Web 端使用；Android 有全量缓存时在本地搜索）。
 *
 * @property keyword 关键字
 * @property max 最多返回条数
 */
class SearchEmployeesRequest(
    val keyword: String,
    val max: Int = 20,
) : IqRequest<List<OrgEmployee>>(IqType.GET, "search", ORG_NAMESPACE) {

    override val attributes: Map<String, String> = mapOf("keyword" to keyword)

    override fun childrenXml(): String = Rsm.requestXml(max)

    /**
     * 解析结果。
     *
     * @param result 结果子元素
     * @return 员工列表，匹配度高的在前
     */
    override fun parseResult(result: XmlNode?): List<OrgEmployee> =
        requireOrgResult(result, elementName).childrenNamed("employee").map(OrgXml::employee)
}
