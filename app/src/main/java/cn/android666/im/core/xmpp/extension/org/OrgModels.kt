package cn.android666.im.core.xmpp.extension.org

import cn.android666.im.core.xmpp.iq.XmlNode

/**
 * 部门（协议规范 §4.1）。
 *
 * @property id 部门 ID
 * @property parentId 上级部门 ID，顶级部门为空字符串
 * @property name 部门名称
 * @property sort 同级排序，升序
 * @property memberCount 直属成员数（不含子部门）
 */
data class OrgDepartment(
    val id: String,
    val parentId: String,
    val name: String,
    val sort: Int,
    val memberCount: Int,
)

/**
 * 员工（协议规范 §4.1）。
 *
 * @property jid bare JID
 * @property empNo 工号
 * @property name 姓名
 * @property pinyin 姓名全拼（小写无空格）
 * @property initials 拼音首字母
 * @property deptId 所属部门 ID
 * @property title 职位，可为空字符串
 * @property mobile 手机，可为空字符串
 * @property phone 座机，可为空字符串
 * @property email 邮箱，可为空字符串
 * @property sort 部门内排序，升序
 * @property avatarHash 头像 SHA-1，无头像时为空字符串
 */
data class OrgEmployee(
    val jid: String,
    val empNo: String,
    val name: String,
    val pinyin: String,
    val initials: String,
    val deptId: String,
    val title: String,
    val mobile: String,
    val phone: String,
    val email: String,
    val sort: Int,
    val avatarHash: String,
)

/** 同步结果中的一项（协议规范 §4.4）：部门 / 员工的新增或更新、删除 */
sealed interface OrgSyncEntry {

    /**
     * 部门新增或更新。
     *
     * @property department 部门
     */
    data class DepartmentUpsert(val department: OrgDepartment) : OrgSyncEntry

    /**
     * 部门删除。
     *
     * @property id 部门 ID
     */
    data class DepartmentDelete(val id: String) : OrgSyncEntry

    /**
     * 员工新增或更新。
     *
     * @property employee 员工
     */
    data class EmployeeUpsert(val employee: OrgEmployee) : OrgSyncEntry

    /**
     * 员工删除（离职、禁用）。
     *
     * @property jid 员工 bare JID
     */
    data class EmployeeDelete(val jid: String) : OrgSyncEntry
}

/** 部门 / 员工元素的解析（多个请求共用） */
internal object OrgXml {

    /** 同步结果中的删除操作 */
    const val OP_DELETE = "delete"

    /**
     * 解析 `<department/>`。
     *
     * @param node 元素
     * @return 部门
     * @throws IllegalArgumentException 缺少 id
     */
    fun department(node: XmlNode): OrgDepartment = OrgDepartment(
        id = requireNotNull(node.attr("id")) { "部门缺少 id：$node" },
        parentId = node.attr("parentId").orEmpty(),
        name = node.attr("name").orEmpty(),
        sort = node.attr("sort")?.toIntOrNull() ?: 0,
        memberCount = node.attr("memberCount")?.toIntOrNull() ?: 0,
    )

    /**
     * 解析 `<employee/>`。工号缺失时取 JID 的用户名部分。
     *
     * @param node 元素
     * @return 员工
     * @throws IllegalArgumentException 缺少 jid
     */
    fun employee(node: XmlNode): OrgEmployee {
        val jid = requireNotNull(node.attr("jid")) { "员工缺少 jid：$node" }
        return OrgEmployee(
            jid = jid,
            empNo = node.attr("empNo") ?: jid.substringBefore('@'),
            name = node.attr("name").orEmpty(),
            pinyin = node.attr("pinyin").orEmpty(),
            initials = node.attr("initials").orEmpty(),
            deptId = node.attr("deptId").orEmpty(),
            title = node.attr("title").orEmpty(),
            mobile = node.attr("mobile").orEmpty(),
            phone = node.attr("phone").orEmpty(),
            email = node.attr("email").orEmpty(),
            sort = node.attr("sort")?.toIntOrNull() ?: 0,
            avatarHash = node.attr("avatarHash").orEmpty(),
        )
    }
}
