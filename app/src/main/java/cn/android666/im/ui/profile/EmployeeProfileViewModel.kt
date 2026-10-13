package cn.android666.im.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.data.org.Employee
import cn.android666.im.data.org.OrgRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 同事资料卡的内容。
 *
 * @property employee 员工；为 null 时表示不在组织架构中（已离职或未同步）
 * @property departmentPath 部门完整路径
 * @property self 是否为本人（本人不显示"发消息""打电话"）
 */
data class ProfileUiState(val employee: Employee?, val departmentPath: String, val self: Boolean)

/**
 * 同事资料卡（开发计划 3.18）：员工信息、发消息、打电话；在线状态在 3.20 中加入。
 *
 * @property org 组织架构
 * @property jid 同事的 bare JID
 * @property selfJid 本人的 bare JID（未登录时为 null）
 */
class EmployeeProfileViewModel(
    private val org: OrgRepository,
    private val jid: String,
    private val selfJid: String?,
) : ViewModel() {

    /** 资料卡内容；还没读出时为 null */
    val state: StateFlow<ProfileUiState?> = org.observeEmployee(jid)
        .map { employee ->
            ProfileUiState(
                employee = employee,
                departmentPath = employee?.let { org.departmentPath(it.deptId) }.orEmpty(),
                self = jid == selfJid,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private companion object {
        /** 页面不可见后多久停止收集 */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
