package cn.android666.im.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.data.org.Department
import cn.android666.im.data.org.Employee
import cn.android666.im.data.org.OrgRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * 面包屑中的一级。
 *
 * @property id 部门 ID，根（全部顶级部门）为空字符串
 * @property name 部门名称，根为 null（界面显示"组织架构"）
 */
data class Crumb(val id: String, val name: String?)

/** 组织架构列表中的一项：下级部门在前，部门成员在后 */
sealed interface OrgItem {

    /**
     * 下级部门。
     *
     * @property department 部门
     */
    data class Dept(val department: Department) : OrgItem

    /**
     * 部门成员。
     *
     * @property employee 员工
     */
    data class Member(val employee: Employee) : OrgItem
}

/**
 * 组织架构浏览（开发计划 3.17）：从顶级部门开始逐级进入，顶部面包屑可以跳回任一级，返回键回到上一级。
 *
 * @property org 组织架构
 */
class OrgBrowseViewModel(private val org: OrgRepository) : ViewModel() {

    private val stack = MutableStateFlow(listOf(Crumb(ROOT, null)))

    /** 面包屑（从根到当前部门） */
    val crumbs: StateFlow<List<Crumb>> = stack.asStateFlow()

    /** 当前部门的下级部门和直属成员 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<OrgItem>> = stack
        .flatMapLatest { crumbs ->
            val current = crumbs.last().id
            // 根下只有顶级部门，没有成员
            val members = if (current == ROOT) flowOf(emptyList()) else org.observeMembers(current)
            combine(org.observeChildren(current), members) { departments, employees ->
                departments.map(OrgItem::Dept) + employees.map(OrgItem::Member)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * 进入下级部门。
     *
     * @param department 部门
     */
    fun open(department: Department) {
        stack.value = stack.value + Crumb(department.id, department.name)
    }

    /**
     * 返回上一级。
     *
     * @return 是否返回了（已在根时返回 false，由页面关闭）
     */
    fun back(): Boolean {
        val current = stack.value
        if (current.size <= 1) return false
        stack.value = current.dropLast(1)
        return true
    }

    /**
     * 跳到面包屑中的某一级。
     *
     * @param index 面包屑下标
     */
    fun jumpTo(index: Int) {
        val current = stack.value
        if (index in current.indices) stack.value = current.take(index + 1)
    }

    private companion object {
        /** 根：全部顶级部门 */
        const val ROOT = ""

        /** 页面不可见后多久停止收集 */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
