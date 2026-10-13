package cn.android666.im.ui.conversation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.data.conversation.ConversationItem
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.databinding.FragmentConversationsBinding
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.base.appContainer
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.chat.ChatActivity

/**
 * 主界面"消息" Tab：会话列表（开发计划 4.7，功能清单 §10）。点击进入聊天页，长按弹出操作菜单。
 */
class ConversationListFragment : Fragment() {

    private val log = Logs.file(TAG)

    private val viewModel by viewModels<ConversationListViewModel> {
        viewModelFactory {
            initializer { ConversationListViewModel(requireContext().appContainer.conversationRepository) }
        }
    }

    private var binding: FragmentConversationsBinding? = null

    /**
     * 创建视图。
     *
     * @param inflater 布局加载器
     * @param container 父容器
     * @param savedInstanceState 保存的状态
     * @return 页面视图
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentConversationsBinding.inflate(inflater, container, false)
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
        val adapter = ConversationAdapter(
            now = System::currentTimeMillis,
            onClick = ::openConversation,
            onLongClick = { item, name ->
                ConversationActionsDialogFragment.show(childFragmentManager, item.id, name, item.unreadCount > 0)
            },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        binding.list.adapter = adapter

        childFragmentManager.setFragmentResultListener(ConversationActionsDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val id = result.getString(ConversationActionsDialogFragment.RESULT_ID) ?: return@setFragmentResultListener
            when (result.getString(ConversationActionsDialogFragment.RESULT_ACTION)) {
                ConversationActionsDialogFragment.ACTION_MARK_READ -> viewModel.markRead(id)
                ConversationActionsDialogFragment.ACTION_DELETE -> confirmDelete(id)
            }
        }
        childFragmentManager.setFragmentResultListener(REQUEST_DELETE, viewLifecycleOwner) { _, result ->
            val id = viewModel.pendingDeleteId
            viewModel.pendingDeleteId = null
            if (id != null && result.getBoolean(MessageDialogFragment.RESULT_POSITIVE)) viewModel.delete(id)
        }

        collectWhenStarted(viewModel.conversations) { list ->
            if (list == null) return@collectWhenStarted
            adapter.submitList(list)
            binding.empty.isVisible = list.isEmpty()
        }
    }

    /**
     * 删除前确认（会清空本地聊天记录）。
     *
     * @param id 会话 ID
     */
    private fun confirmDelete(id: String) {
        viewModel.pendingDeleteId = id
        MessageDialogFragment.show(
            fragmentManager = childFragmentManager,
            requestKey = REQUEST_DELETE,
            title = R.string.conversation_action_delete,
            message = R.string.conversation_delete_confirm,
            positive = R.string.conversation_delete,
            negative = R.string.cancel,
            cancelable = true,
        )
    }

    /**
     * 打开会话的聊天页；群聊（M6）暂未提供。
     *
     * @param item 会话
     */
    private fun openConversation(item: ConversationItem) {
        log.info("打开会话：{}", item.id)
        if (item.kind == ConversationKind.GROUP) {
            Toast.makeText(requireContext(), R.string.feature_pending, Toast.LENGTH_SHORT).show()
            return
        }
        ChatActivity.start(requireContext(), item.id, item.kind)
    }

    /** 销毁视图时释放 binding */
    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val TAG = "ConversationList"

        /** 删除确认框的结果 key */
        const val REQUEST_DELETE = "deleteConversation"
    }
}
