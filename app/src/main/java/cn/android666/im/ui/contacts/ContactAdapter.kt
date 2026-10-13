package cn.android666.im.ui.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.R
import cn.android666.im.data.contact.Contact
import cn.android666.im.databinding.ItemEmployeeBinding
import cn.android666.im.ui.widget.NameAvatar

/**
 * 常用联系人列表：有员工信息的显示姓名和职位，已离职（不在组织架构中）的显示工号和"已离职"。
 *
 * @property onClick 点击联系人（打开资料卡）
 */
class ContactAdapter(private val onClick: (Contact) -> Unit) : ListAdapter<Contact, ContactAdapter.Holder>(DIFF) {

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
        val contact = getItem(position)
        val binding = holder.binding
        val employee = contact.employee
        if (employee != null) {
            EmployeeAdapter.bindEmployee(binding, employee) { onClick(contact) }
        } else {
            val name = contact.jid.substringBefore('@')
            NameAvatar.bind(binding.avatar, name, contact.jid)
            binding.name.text = name
            binding.subtitle.setText(R.string.contact_left)
            binding.root.setOnClickListener { onClick(contact) }
        }
    }

    private companion object {
        /** 按 JID 判断是否同一项 */
        val DIFF = object : DiffUtil.ItemCallback<Contact>() {
            override fun areItemsTheSame(oldItem: Contact, newItem: Contact) = oldItem.jid == newItem.jid
            override fun areContentsTheSame(oldItem: Contact, newItem: Contact) = oldItem == newItem
        }
    }
}
