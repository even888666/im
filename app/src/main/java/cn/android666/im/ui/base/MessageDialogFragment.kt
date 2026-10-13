package cn.android666.im.ui.base

import android.app.Dialog
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 通用的提示 / 确认对话框。
 *
 * 用 DialogFragment 而不是直接 show AlertDialog：旋转屏幕等页面重建后对话框仍在，点击结果通过
 * Fragment Result API 返回给页面（页面重建后重新注册监听也能收到）。
 *
 * 结果：requestKey 为 [show] 传入的 key，Bundle 中 [RESULT_POSITIVE] 为 true 表示点了确定按钮。
 */
class MessageDialogFragment : DialogFragment() {

    /**
     * 创建对话框。
     *
     * @param savedInstanceState 保存的状态
     * @return 对话框
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY).orEmpty()
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setMessage(args.getInt(ARG_MESSAGE))
            .setPositiveButton(args.getInt(ARG_POSITIVE)) { _, _ ->
                setFragmentResult(requestKey, bundleOf(RESULT_POSITIVE to true))
            }
        val title = args.getInt(ARG_TITLE)
        if (title != 0) builder.setTitle(title)
        val negative = args.getInt(ARG_NEGATIVE)
        if (negative != 0) {
            builder.setNegativeButton(negative) { _, _ ->
                setFragmentResult(requestKey, bundleOf(RESULT_POSITIVE to false))
            }
        }
        isCancelable = args.getBoolean(ARG_CANCELABLE)
        return builder.create().apply { setCanceledOnTouchOutside(isCancelable) }
    }

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE = "positive"
        private const val ARG_NEGATIVE = "negative"
        private const val ARG_CANCELABLE = "cancelable"

        /** 结果 Bundle 中的键：是否点了确定按钮 */
        const val RESULT_POSITIVE = "positive"

        /**
         * 显示对话框；同一个 [requestKey] 的对话框已经在显示时不重复显示。
         *
         * @param fragmentManager Activity 的 supportFragmentManager 或 Fragment 的 childFragmentManager
         * @param requestKey 结果的 key，同时作为 Fragment 的 tag
         * @param title 标题，0 表示没有标题
         * @param message 内容
         * @param positive 确定按钮文字
         * @param negative 取消按钮文字，0 表示没有取消按钮
         * @param cancelable 能否点返回键或对话框外部关闭（关闭时没有结果）
         */
        fun show(
            fragmentManager: FragmentManager,
            requestKey: String,
            @StringRes title: Int,
            @StringRes message: Int,
            @StringRes positive: Int,
            @StringRes negative: Int = 0,
            cancelable: Boolean = false,
        ) {
            if (fragmentManager.findFragmentByTag(requestKey) != null) return
            MessageDialogFragment().apply {
                arguments = bundleOf(
                    ARG_REQUEST_KEY to requestKey,
                    ARG_TITLE to title,
                    ARG_MESSAGE to message,
                    ARG_POSITIVE to positive,
                    ARG_NEGATIVE to negative,
                    ARG_CANCELABLE to cancelable,
                )
            }.show(fragmentManager, requestKey)
        }
    }
}
