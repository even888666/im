package cn.android666.im.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cn.android666.im.R
import cn.android666.im.databinding.FragmentMeBinding
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.base.appActivityViewModels
import cn.android666.im.ui.base.collectWhenStarted

/**
 * "我"页面：显示工号、退出登录（开发计划 2.23）。个人资料、设置、关于等在后续模块加入。
 */
class MeFragment : Fragment() {

    private val viewModel by appActivityViewModels(MainActivity::createViewModel)

    private var binding: FragmentMeBinding? = null

    /**
     * 创建视图。
     *
     * @param inflater 布局加载器
     * @param container 父容器
     * @param savedInstanceState 保存的状态
     * @return 页面视图
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentMeBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    /**
     * 视图创建后绑定数据和点击事件。
     *
     * @param view 页面视图
     * @param savedInstanceState 保存的状态
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        // 退出登录前确认（退出后不再接收消息）
        binding.logoutButton.setOnClickListener {
            MessageDialogFragment.show(
                fragmentManager = childFragmentManager,
                requestKey = REQUEST_LOGOUT,
                title = R.string.me_logout,
                message = R.string.me_logout_confirm,
                positive = R.string.me_logout,
                negative = R.string.cancel,
                cancelable = true,
            )
        }
        childFragmentManager.setFragmentResultListener(REQUEST_LOGOUT, viewLifecycleOwner) { _, result ->
            if (result.getBoolean(MessageDialogFragment.RESULT_POSITIVE)) viewModel.logout()
        }
        viewLifecycleOwner.collectWhenStarted(viewModel.username) { username ->
            binding.empNoText.text = getString(R.string.me_emp_no, username.orEmpty())
        }
    }

    /** 视图销毁时释放 binding，避免内存泄漏 */
    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private companion object {
        /** 退出登录确认框的结果 key */
        const val REQUEST_LOGOUT = "logout"
    }
}
