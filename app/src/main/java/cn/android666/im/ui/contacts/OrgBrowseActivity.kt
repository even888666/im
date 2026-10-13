package cn.android666.im.ui.contacts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import cn.android666.im.R
import cn.android666.im.databinding.ActivityOrgBrowseBinding
import cn.android666.im.ui.base.appViewModels
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.profile.EmployeeProfileActivity

/**
 * 组织架构浏览（开发计划 3.17）：逐级进入部门，顶部面包屑可跳回任一级；返回键回到上一级，在顶级时关闭页面。
 */
class OrgBrowseActivity : AppCompatActivity() {

    private val viewModel by appViewModels { OrgBrowseViewModel(it.orgRepository) }

    private lateinit var binding: ActivityOrgBrowseBinding

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOrgBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsPadding(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.back()) finish()
            }
        })

        val adapter = OrgItemAdapter(
            onDepartmentClick = viewModel::open,
            onEmployeeClick = { EmployeeProfileActivity.start(this, it.jid) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        collectWhenStarted(viewModel.items) { items ->
            adapter.submitList(items)
            binding.empty.isVisible = items.isEmpty()
        }
        collectWhenStarted(viewModel.crumbs, ::renderCrumbs)
    }

    /**
     * 显示面包屑：每一级是一个可点击的文字，最后一级（当前部门）高亮且不可点击。
     *
     * @param crumbs 面包屑
     */
    private fun renderCrumbs(crumbs: List<Crumb>) {
        val container = binding.crumbs
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        crumbs.forEachIndexed { index, crumb ->
            val last = index == crumbs.lastIndex
            val view = inflater.inflate(R.layout.item_crumb, container, false) as TextView
            view.text = crumb.name ?: getString(R.string.contacts_org)
            view.isEnabled = !last
            if (!last) {
                view.setOnClickListener { viewModel.jumpTo(index) }
            }
            container.addView(view)
            if (!last) {
                container.addView(inflater.inflate(R.layout.item_crumb_separator, container, false))
            }
        }
        // 滚动到最后一级
        binding.crumbsScroll.post { binding.crumbsScroll.fullScroll(android.view.View.FOCUS_RIGHT) }
    }

    companion object {
        /**
         * 打开组织架构。
         *
         * @param context Context
         */
        fun start(context: Context) {
            context.startActivity(Intent(context, OrgBrowseActivity::class.java))
        }
    }
}
