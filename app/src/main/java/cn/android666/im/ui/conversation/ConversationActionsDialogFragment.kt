package cn.android666.im.ui.conversation

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import cn.android666.im.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 长按会话弹出的操作菜单（标为已读、删除会话）。结果通过 Fragment Result API 返回：
 * requestKey 为 [REQUEST_KEY]，Bundle 中 [RESULT_ACTION] 为选中的操作、[RESULT_ID] 为会话 ID。
 */
class ConversationActionsDialogFragment : DialogFragment() {

    /**
     * 创建对话框。
     *
     * @param savedInstanceState 保存的状态
     * @return 对话框
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val id = args.getString(ARG_ID).orEmpty()
        val actions = buildList {
            if (args.getBoolean(ARG_HAS_UNREAD)) add(ACTION_MARK_READ)
            add(ACTION_DELETE)
        }
        val labels = actions.map {
            getString(if (it == ACTION_MARK_READ) R.string.conversation_action_mark_read else R.string.conversation_action_delete)
        }
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(args.getString(ARG_TITLE))
            .setItems(labels.toTypedArray()) { _, which ->
                setFragmentResult(REQUEST_KEY, bundleOf(RESULT_ACTION to actions[which], RESULT_ID to id))
            }
            .create()
    }

    companion object {
        /** 结果的 key */
        const val REQUEST_KEY = "conversationActions"

        /** 结果：操作（[ACTION_MARK_READ] / [ACTION_DELETE]） */
        const val RESULT_ACTION = "action"

        /** 结果：会话 ID */
        const val RESULT_ID = "id"

        /** 操作：标为已读 */
        const val ACTION_MARK_READ = "markRead"

        /** 操作：删除会话 */
        const val ACTION_DELETE = "delete"

        private const val ARG_ID = "id"
        private const val ARG_TITLE = "title"
        private const val ARG_HAS_UNREAD = "hasUnread"

        /**
         * 显示菜单。
         *
         * @param fragmentManager 会话列表的 childFragmentManager
         * @param id 会话 ID
         * @param title 菜单标题（会话名称）
         * @param hasUnread 是否有未读（有时才显示"标为已读"）
         */
        fun show(fragmentManager: FragmentManager, id: String, title: String, hasUnread: Boolean) {
            if (fragmentManager.findFragmentByTag(REQUEST_KEY) != null) return
            ConversationActionsDialogFragment().apply {
                arguments = bundleOf(ARG_ID to id, ARG_TITLE to title, ARG_HAS_UNREAD to hasUnread)
            }.show(fragmentManager, REQUEST_KEY)
        }
    }
}
