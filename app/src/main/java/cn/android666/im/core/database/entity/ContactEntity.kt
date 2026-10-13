package cn.android666.im.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 花名册条目（常用联系人，Android工程架构.md §5.2 的 contact 表）。只有订阅状态为 to / both 的显示为常用联系人。
 *
 * @property jid 联系人 bare JID
 * @property subscription 订阅状态（none / to / from / both，小写）
 */
@Entity(tableName = "contact")
data class ContactEntity(
    @PrimaryKey val jid: String,
    val subscription: String,
)

/**
 * 常用联系人及其组织架构信息（左连接员工表：已离职或未同步的联系人员工信息为 null）。
 *
 * @property jid 联系人 bare JID
 * @property employee 员工信息
 */
data class ContactWithEmployee(
    val jid: String,
    @Embedded(prefix = "e_") val employee: EmployeeEntity?,
)
