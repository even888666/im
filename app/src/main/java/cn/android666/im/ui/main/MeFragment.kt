package cn.android666.im.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.IOException
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.android666.im.R
import cn.android666.im.databinding.FragmentMeBinding
import cn.android666.im.ui.base.MessageDialogFragment
import cn.android666.im.ui.base.appActivityViewModels
import cn.android666.im.ui.base.appContainer
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.common.PresenceText

/**
 * "我"页面：头像（点击更换，开发计划 3.21）、姓名、工号、我的状态（3.20）、退出登录（2.23）。设置、关于等在后续模块加入。
 */
class MeFragment : Fragment() {

    private val viewModel by appActivityViewModels(MainActivity::createViewModel)

    private val meViewModel by viewModels<MeViewModel> {
        viewModelFactory {
            initializer {
                val container = requireContext().appContainer
                val self = container.sessionManager.currentUsername?.let { "$it@${container.serverConfig.domain}" }
                MeViewModel(container.presenceRepository, container.orgRepository, container.avatarRepository, self)
            }
        }
    }

    private var binding: FragmentMeBinding? = null

    /** 系统图片选择器（Android 13+ 为照片选择器，低版本自动退回到文件选择） */
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        val data = try {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
        if (data == null || data.size > MAX_IMAGE_BYTES) {
            Toast.makeText(requireContext(), R.string.me_avatar_invalid, Toast.LENGTH_SHORT).show()
        } else {
            meViewModel.setAvatar(data)
        }
    }

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
        binding.avatar.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        viewLifecycleOwner.collectWhenStarted(meViewModel.me) { me ->
            val username = viewModel.username.value.orEmpty()
            val name = me?.name ?: username
            binding.nameText.text = name
            binding.avatar.bind(name, me?.jid ?: username, me?.avatarHash.orEmpty())
        }
        binding.statusRow.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(MyStatusDialogFragment.TAG) == null) {
                MyStatusDialogFragment().show(childFragmentManager, MyStatusDialogFragment.TAG)
            }
        }
        viewLifecycleOwner.collectWhenStarted(meViewModel.myStatus) { setting ->
            val label = getString(PresenceText.label(setting.status))
            binding.statusText.text = if (setting.text.isBlank()) label else "$label · ${setting.text}"
        }
        viewLifecycleOwner.collectWhenStarted(meViewModel.messages) {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
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
        /** 选择的图片大小上限（20 MB），过大的图片解码可能内存不足 */
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

        /** 退出登录确认框的结果 key */
        const val REQUEST_LOGOUT = "logout"
    }
}
