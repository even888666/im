package cn.android666.im.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import cn.android666.im.databinding.FragmentPlaceholderBinding

/**
 * 尚未实现的 Tab 的占位页（消息列表在 M4、通讯录在 M3 中实现后替换）。
 */
class PlaceholderFragment : Fragment() {

    /**
     * 创建视图：居中显示参数中的说明文字。
     *
     * @param inflater 布局加载器
     * @param container 父容器
     * @param savedInstanceState 保存的状态
     * @return 页面视图
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        binding.placeholderText.setText(requireArguments().getInt(ARG_TEXT))
        return binding.root
    }

    companion object {
        private const val ARG_TEXT = "text"

        /**
         * 创建占位页。
         *
         * @param text 显示的说明文字
         * @return 占位页
         */
        fun newInstance(@StringRes text: Int): PlaceholderFragment =
            PlaceholderFragment().apply { arguments = bundleOf(ARG_TEXT to text) }
    }
}
