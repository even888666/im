package cn.android666.im.ui.forward

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.android666.im.R
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.databinding.ActivityForwardTargetBinding
import cn.android666.im.databinding.ItemEmployeeBinding
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.base.appContainer
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.base.collectWhenStarted

/**
 * 选择转发对象（开发计划 4.16）：点击后确认"发送给 xx？"，确定后把目标返回给聊天页。
 */
class ForwardTargetActivity : AppCompatActivity() {

    private val viewModel by viewModels<ForwardTargetViewModel> {
        viewModelFactory {
            initializer {
                val container = appContainer
                ForwardTargetViewModel(container.conversationRepository, container.orgRepository, container.selfJid())
            }
        }
    }

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityForwardTargetBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsPadding(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = TargetAdapter { target ->
            viewModel.pending = target
            MessageDialogFragment.showText(
                fragmentManager = supportFragmentManager,
                requestKey = REQUEST_CONFIRM,
                message = getString(R.string.forward_confirm, displayName(target)),
                positive = R.string.forward_send,
                negative = R.string.cancel,
            )
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.searchInput.doAfterTextChanged { viewModel.onQueryChanged(it?.toString().orEmpty()) }
        collectWhenStarted(viewModel.targets, adapter::submitList)

        supportFragmentManager.setFragmentResultListener(REQUEST_CONFIRM, this) { _, result ->
            val target = viewModel.pending
            viewModel.pending = null
            if (target != null && result.getBoolean(MessageDialogFragment.RESULT_POSITIVE)) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ID, target.id).putExtra(EXTRA_KIND, target.kind.name))
                finish()
            }
        }
    }

    /**
     * 目标的显示名称。
     *
     * @param target 目标
     * @return 名称
     */
    private fun displayName(target: ForwardTarget): String = target.name ?: getString(R.string.conversation_self)

    /**
     * 目标列表。
     *
     * @property onClick 点击目标
     */
    private inner class TargetAdapter(private val onClick: (ForwardTarget) -> Unit) :
        ListAdapter<ForwardTarget, TargetAdapter.Holder>(DIFF) {

        /**
         * 列表项。
         *
         * @property binding 布局
         */
        inner class Holder(val binding: ItemEmployeeBinding) : RecyclerView.ViewHolder(binding.root)

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
            val target = getItem(position)
            val name = displayName(target)
            holder.binding.avatar.bind(name, target.id, target.avatarHash)
            holder.binding.name.text = name
            holder.binding.subtitle.text = if (target.kind == ConversationKind.SELF) "" else target.id.substringBefore('@')
            holder.binding.root.setOnClickListener { onClick(target) }
        }
    }

    /** 选择转发对象：输入为空，输出为目标（取消时为 null） */
    class Contract : ActivityResultContract<Unit, ForwardTarget?>() {

        /**
         * 创建打开页面的 Intent。
         *
         * @param context Context
         * @param input 无
         * @return Intent
         */
        override fun createIntent(context: Context, input: Unit): Intent = Intent(context, ForwardTargetActivity::class.java)

        /**
         * 解析结果。
         *
         * @param resultCode 结果码
         * @param intent 结果
         * @return 目标；取消时为 null
         */
        override fun parseResult(resultCode: Int, intent: Intent?): ForwardTarget? {
            if (resultCode != Activity.RESULT_OK || intent == null) return null
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            val kind = intent.getStringExtra(EXTRA_KIND)?.let(ConversationKind::valueOf) ?: ConversationKind.SINGLE
            return ForwardTarget(id, kind, null, "")
        }
    }

    private companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"

        /** 确认框的结果 key */
        const val REQUEST_CONFIRM = "confirmForward"

        /** 按会话 ID 判断是否同一项 */
        val DIFF = object : DiffUtil.ItemCallback<ForwardTarget>() {
            override fun areItemsTheSame(oldItem: ForwardTarget, newItem: ForwardTarget) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ForwardTarget, newItem: ForwardTarget) = oldItem == newItem
        }
    }
}
