package cn.android666.im.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import cn.android666.im.R
import cn.android666.im.databinding.FragmentContactsBinding
import cn.android666.im.ui.base.appContainer
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.profile.EmployeeProfileActivity

/**
 * 通讯录首页（开发计划 3.16）：搜索同事、组织架构入口、我的群组入口（M6）、常用联系人（3.19），下拉刷新同步组织架构。
 */
class ContactsFragment : Fragment() {

    private val viewModel by viewModels<ContactsViewModel> {
        viewModelFactory { initializer { ContactsViewModel(requireContext().appContainer.orgRepository) } }
    }

    private var binding: FragmentContactsBinding? = null

    /**
     * 创建视图。
     *
     * @param inflater 布局加载器
     * @param container 父容器
     * @param savedInstanceState 保存的状态
     * @return 页面视图
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentContactsBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    /**
     * 视图创建后绑定数据和事件。
     *
     * @param view 页面视图
     * @param savedInstanceState 保存的状态
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        val adapter = EmployeeAdapter { EmployeeProfileActivity.start(requireContext(), it.jid) }
        binding.results.layoutManager = LinearLayoutManager(requireContext())
        binding.results.adapter = adapter

        binding.searchInput.doAfterTextChanged { viewModel.onQueryChanged(it?.toString().orEmpty()) }
        binding.entryOrg.setOnClickListener { OrgBrowseActivity.start(requireContext()) }
        binding.entryGroups.setOnClickListener {
            Toast.makeText(requireContext(), R.string.feature_pending, Toast.LENGTH_SHORT).show()
        }

        binding.refresh.setOnRefreshListener { viewModel.refresh() }
        // 内容区域可以向上滚动时不触发下拉刷新（列表在 FrameLayout 中，SwipeRefreshLayout 默认判断不到）
        binding.refresh.setOnChildScrollUpCallback { _, _ ->
            if (binding.results.isVisible) binding.results.canScrollVertically(-1) else binding.entries.canScrollVertically(-1)
        }

        viewLifecycleOwner.collectWhenStarted(viewModel.results) { results ->
            val searching = results != null
            binding.entries.isVisible = !searching
            binding.results.isVisible = searching && results!!.isNotEmpty()
            binding.resultsEmpty.isVisible = searching && results!!.isEmpty()
            adapter.submitList(results.orEmpty())
        }
        viewLifecycleOwner.collectWhenStarted(viewModel.refreshing) { binding.refresh.isRefreshing = it }
        viewLifecycleOwner.collectWhenStarted(viewModel.messages) {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
        }
    }

    /** 视图销毁时释放 binding */
    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
