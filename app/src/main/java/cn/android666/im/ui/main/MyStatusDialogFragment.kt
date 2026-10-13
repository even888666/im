package cn.android666.im.ui.main

import android.app.Dialog
import android.os.Bundle
import android.widget.RadioButton
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import cn.android666.im.R
import cn.android666.im.data.presence.MyStatus
import cn.android666.im.data.presence.MyStatusSetting
import cn.android666.im.databinding.DialogMyStatusBinding
import cn.android666.im.ui.common.PresenceText
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 修改本人在线状态的对话框（协议规范 §5.2）：选择在线 / 忙碌 / 开会中 / 出差 / 离开，可填写一句说明。
 * 由 [MeFragment] 以子 Fragment 显示，与它共用 [MeViewModel]。
 */
class MyStatusDialogFragment : DialogFragment() {

    private val viewModel by viewModels<MeViewModel>(ownerProducer = { requireParentFragment() })

    /**
     * 创建对话框：每个状态一个单选按钮，默认选中当前状态。
     *
     * @param savedInstanceState 保存的状态
     * @return 对话框
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogMyStatusBinding.inflate(layoutInflater)
        val current = viewModel.myStatus.value
        MyStatus.entries.forEach { status ->
            val button = RadioButton(requireContext()).apply {
                id = status.ordinal + 1
                setText(PresenceText.label(status))
            }
            binding.statusGroup.addView(button)
        }
        if (savedInstanceState == null) {
            binding.statusGroup.check(current.status.ordinal + 1)
            binding.textInput.setText(current.text)
        }
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.me_status)
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val status = MyStatus.entries.getOrNull(binding.statusGroup.checkedRadioButtonId - 1) ?: MyStatus.ONLINE
                viewModel.setStatus(MyStatusSetting(status, binding.textInput.text?.toString().orEmpty()))
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    companion object {
        /** Fragment 的 tag */
        const val TAG = "myStatus"
    }
}
