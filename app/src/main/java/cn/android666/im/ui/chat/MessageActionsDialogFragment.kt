package cn.android666.im.ui.chat

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import cn.android666.im.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 长按消息弹出的操作菜单（复制、撤回）。结果通过 Fragment Result API 返回：
 * requestKey 为 [REQUEST_KEY]，Bundle 中 [RESULT_ACTION] 为选中的操作、[RESULT_ROW_ID] 为消息 rowId、[RESULT_TEXT] 为消息文字。
 */
class MessageActionsDialogFragment : DialogFragment() {

    /**
     * 创建对话框。
     *
     * @param savedInstanceState 保存的状态
     * @return 对话框
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val actions = args.getStringArray(ARG_ACTIONS).orEmpty()
        val labels = actions.map { getString(if (it == ACTION_RETRACT) R.string.chat_action_retract else R.string.chat_action_copy) }
        return MaterialAlertDialogBuilder(requireContext())
            .setItems(labels.toTypedArray()) { _, which ->
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(
                        RESULT_ACTION to actions[which],
                        RESULT_ROW_ID to args.getLong(ARG_ROW_ID),
                        RESULT_TEXT to args.getString(ARG_TEXT),
                    ),
                )
            }
            .create()
    }

    companion object {
        /** 结果的 key */
        const val REQUEST_KEY = "messageActions"

        /** 结果：操作（[ACTION_COPY] / [ACTION_RETRACT]） */
        const val RESULT_ACTION = "action"

        /** 结果：消息 rowId */
        const val RESULT_ROW_ID = "rowId"

        /** 结果：消息文字（复制用） */
        const val RESULT_TEXT = "text"

        /** 操作：复制 */
        const val ACTION_COPY = "copy"

        /** 操作：撤回 */
        const val ACTION_RETRACT = "retract"

        private const val ARG_ACTIONS = "actions"
        private const val ARG_ROW_ID = "rowId"
        private const val ARG_TEXT = "text"

        /**
         * 显示菜单；没有可用操作时不显示。
         *
         * @param fragmentManager Activity 的 supportFragmentManager
         * @param rowId 消息 rowId
         * @param text 消息文字
         * @param actions 可用的操作
         */
        fun show(fragmentManager: FragmentManager, rowId: Long, text: String, actions: List<String>) {
            if (actions.isEmpty() || fragmentManager.findFragmentByTag(REQUEST_KEY) != null) return
            MessageActionsDialogFragment().apply {
                arguments = bundleOf(ARG_ACTIONS to actions.toTypedArray(), ARG_ROW_ID to rowId, ARG_TEXT to text)
            }.show(fragmentManager, REQUEST_KEY)
        }
    }
}
