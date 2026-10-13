package cn.android666.im.ui.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.R
import cn.android666.im.data.org.Employee
import cn.android666.im.databinding.ItemEmployeeBinding

/**
 * 员工列表（通讯录搜索结果）：头像、姓名、"职位 · 工号"。
 *
 * @property onClick 点击员工
 */
class EmployeeAdapter(private val onClick: (Employee) -> Unit) :
    ListAdapter<Employee, EmployeeAdapter.Holder>(DIFF) {

    /**
     * 列表项。
     *
     * @property binding 布局
     */
    class Holder(val binding: ItemEmployeeBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 创建列表项。
     *
     * @param parent 父容器
     * @param viewType 类型（只有一种）
     * @return 列表项
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemEmployeeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    /**
     * 绑定数据。
     *
     * @param holder 列表项
     * @param position 位置
     */
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val employee = getItem(position)
        bindEmployee(holder.binding, employee, onClick)
    }

    companion object {
        /** 按 JID 判断是否同一项 */
        val DIFF = object : DiffUtil.ItemCallback<Employee>() {
            override fun areItemsTheSame(oldItem: Employee, newItem: Employee) = oldItem.jid == newItem.jid
            override fun areContentsTheSame(oldItem: Employee, newItem: Employee) = oldItem == newItem
        }

        /**
         * 把员工显示到列表项上（搜索结果和部门成员共用）。
         *
         * @param binding 列表项布局
         * @param employee 员工
         * @param onClick 点击回调
         */
        fun bindEmployee(binding: ItemEmployeeBinding, employee: Employee, onClick: (Employee) -> Unit) {
            binding.avatar.bind(employee.name, employee.jid, employee.avatarHash)
            binding.name.text = employee.name
            binding.subtitle.text = if (employee.title.isEmpty()) {
                employee.empNo
            } else {
                binding.root.context.getString(R.string.employee_subtitle, employee.title, employee.empNo)
            }
            binding.root.setOnClickListener { onClick(employee) }
        }
    }
}
