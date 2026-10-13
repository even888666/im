package cn.android666.im.ui.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.R
import cn.android666.im.data.org.Department
import cn.android666.im.data.org.Employee
import cn.android666.im.databinding.ItemDepartmentBinding
import cn.android666.im.databinding.ItemEmployeeBinding

/**
 * 组织架构列表：下级部门（名称、人数）在前，部门成员在后。
 *
 * @property onDepartmentClick 点击部门（进入下级）
 * @property onEmployeeClick 点击员工（打开资料卡）
 */
class OrgItemAdapter(
    private val onDepartmentClick: (Department) -> Unit,
    private val onEmployeeClick: (Employee) -> Unit,
) : ListAdapter<OrgItem, RecyclerView.ViewHolder>(DIFF) {

    /**
     * 部门列表项。
     *
     * @property binding 布局
     */
    class DeptHolder(val binding: ItemDepartmentBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 员工列表项。
     *
     * @property binding 布局
     */
    class MemberHolder(val binding: ItemEmployeeBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 列表项类型。
     *
     * @param position 位置
     * @return [TYPE_DEPT] 或 [TYPE_MEMBER]
     */
    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is OrgItem.Dept -> TYPE_DEPT
        is OrgItem.Member -> TYPE_MEMBER
    }

    /**
     * 创建列表项。
     *
     * @param parent 父容器
     * @param viewType 类型
     * @return 列表项
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DEPT) {
            DeptHolder(ItemDepartmentBinding.inflate(inflater, parent, false))
        } else {
            MemberHolder(ItemEmployeeBinding.inflate(inflater, parent, false))
        }
    }

    /**
     * 绑定数据。
     *
     * @param holder 列表项
     * @param position 位置
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is OrgItem.Dept -> {
                val binding = (holder as DeptHolder).binding
                binding.name.text = item.department.name
                binding.count.text = binding.root.context.getString(R.string.org_member_count, item.department.memberCount)
                binding.root.setOnClickListener { onDepartmentClick(item.department) }
            }
            is OrgItem.Member -> {
                val binding = (holder as MemberHolder).binding
                EmployeeAdapter.bindEmployee(binding, item.employee, onEmployeeClick)
                // 部门成员列表中只显示职位（部门已在面包屑中）
                binding.subtitle.text = item.employee.title
            }
        }
    }

    private companion object {
        const val TYPE_DEPT = 0
        const val TYPE_MEMBER = 1

        /** 部门按 ID、员工按 JID 判断是否同一项 */
        val DIFF = object : DiffUtil.ItemCallback<OrgItem>() {
            override fun areItemsTheSame(oldItem: OrgItem, newItem: OrgItem): Boolean = when {
                oldItem is OrgItem.Dept && newItem is OrgItem.Dept -> oldItem.department.id == newItem.department.id
                oldItem is OrgItem.Member && newItem is OrgItem.Member -> oldItem.employee.jid == newItem.employee.jid
                else -> false
            }

            override fun areContentsTheSame(oldItem: OrgItem, newItem: OrgItem) = oldItem == newItem
        }
    }
}
