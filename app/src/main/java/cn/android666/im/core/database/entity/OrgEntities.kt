package cn.android666.im.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 部门（组织架构缓存，字段与协议规范 §4.1 一致）。
 *
 * @property id 部门 ID
 * @property parentId 上级部门 ID，顶级部门为空字符串
 * @property name 部门名称
 * @property sort 同级排序，升序
 * @property memberCount 直属成员数（不含子部门）
 */
@Entity(tableName = "department", indices = [Index("parentId")])
data class DepartmentEntity(
    @PrimaryKey val id: String,
    val parentId: String,
    val name: String,
    val sort: Int,
    val memberCount: Int,
)

/**
 * 员工（组织架构缓存，字段与协议规范 §4.1 一致）。只保存在职员工，离职 / 禁用的在同步时删除。
 * 对姓名、拼音、首字母、工号、手机建索引，用于本地搜索（Android工程架构.md §5.2）。
 *
 * @property jid bare JID
 * @property empNo 工号
 * @property name 姓名
 * @property pinyin 姓名全拼
 * @property initials 拼音首字母
 * @property deptId 所属部门 ID
 * @property title 职位
 * @property mobile 手机
 * @property phone 座机
 * @property email 邮箱
 * @property sort 部门内排序，升序
 * @property avatarHash 头像 SHA-1，无头像时为空字符串
 */
@Entity(
    tableName = "employee",
    indices = [Index("deptId"), Index("name"), Index("pinyin"), Index("initials"), Index("empNo"), Index("mobile")],
)
data class EmployeeEntity(
    @PrimaryKey val jid: String,
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

/**
 * 同步游标（如 `org:version`、`mam:self`、`mam:room:{jid}`），Android工程架构.md §5.2。
 *
 * @property key 游标名
 * @property value 游标值
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)
