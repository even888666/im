package cn.android666.im.data.org

import cn.android666.im.core.database.entity.DepartmentEntity
import cn.android666.im.core.database.entity.EmployeeEntity
import cn.android666.im.core.xmpp.extension.org.OrgDepartment
import cn.android666.im.core.xmpp.extension.org.OrgEmployee

/**
 * 部门（界面使用的模型，与数据库实体、协议报文解耦）。
 *
 * @property id 部门 ID
 * @property parentId 上级部门 ID，顶级部门为空字符串
 * @property name 部门名称
 * @property memberCount 直属成员数
 */
data class Department(val id: String, val parentId: String, val name: String, val memberCount: Int)

/**
 * 员工（界面使用的模型）。
 *
 * @property jid bare JID
 * @property empNo 工号
 * @property name 姓名
 * @property pinyin 姓名全拼
 * @property deptId 所属部门 ID
 * @property title 职位
 * @property mobile 手机
 * @property phone 座机
 * @property email 邮箱
 * @property avatarHash 头像 SHA-1，无头像时为空字符串
 */
data class Employee(
    val jid: String,
    val empNo: String,
    val name: String,
    val pinyin: String,
    val deptId: String,
    val title: String,
    val mobile: String,
    val phone: String,
    val email: String,
    val avatarHash: String,
)

/**
 * 实体转模型。
 *
 * @return 部门
 */
internal fun DepartmentEntity.toModel() = Department(id, parentId, name, memberCount)

/**
 * 实体转模型。
 *
 * @return 员工
 */
internal fun EmployeeEntity.toModel() = Employee(jid, empNo, name, pinyin, deptId, title, mobile, phone, email, avatarHash)

/**
 * 报文转实体。
 *
 * @return 部门实体
 */
internal fun OrgDepartment.toEntity() = DepartmentEntity(id, parentId, name, sort, memberCount)

/**
 * 报文转实体。
 *
 * @return 员工实体
 */
internal fun OrgEmployee.toEntity() =
    EmployeeEntity(jid, empNo, name, pinyin, initials, deptId, title, mobile, phone, email, sort, avatarHash)
