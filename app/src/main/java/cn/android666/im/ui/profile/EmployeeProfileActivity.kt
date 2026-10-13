package cn.android666.im.ui.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cn.android666.im.R
import cn.android666.im.databinding.ActivityEmployeeProfileBinding
import cn.android666.im.databinding.ItemProfileFieldBinding
import cn.android666.im.ui.base.appViewModels
import cn.android666.im.ui.base.applySystemBarsPadding
import cn.android666.im.ui.base.collectWhenStarted
import cn.android666.im.ui.widget.NameAvatar
import com.google.android.material.color.MaterialColors

/**
 * 同事资料卡（开发计划 3.18）：姓名、职位、工号、部门、联系方式；发消息（M4 单聊完成后接入）、打电话（系统拨号盘）。
 */
class EmployeeProfileActivity : AppCompatActivity() {

    private val jid: String by lazy { requireNotNull(intent.getStringExtra(EXTRA_JID)) { "缺少参数 jid" } }

    private val viewModel by appViewModels { container ->
        val self = container.sessionManager.currentUsername?.let { "$it@${container.serverConfig.domain}" }
        EmployeeProfileViewModel(container.orgRepository, jid, self)
    }

    private lateinit var binding: ActivityEmployeeProfileBinding

    /**
     * 创建页面。
     *
     * @param savedInstanceState 保存的状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityEmployeeProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsPadding(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        collectWhenStarted(viewModel.state) { state -> state?.let(::render) }
    }

    /**
     * 显示资料。
     *
     * @param state 资料卡内容
     */
    private fun render(state: ProfileUiState) {
        val employee = state.employee
        binding.notFound.isVisible = employee == null
        binding.actions.isVisible = employee != null && !state.self
        binding.fields.removeAllViews()
        if (employee == null) {
            val name = jid.substringBefore('@')
            NameAvatar.bind(binding.avatar, name, jid)
            binding.name.text = name
            binding.headline.text = ""
            return
        }
        NameAvatar.bind(binding.avatar, employee.name, employee.jid)
        binding.name.text = employee.name
        binding.headline.text = employee.title
        addField(R.string.profile_emp_no, employee.empNo, null)
        addField(R.string.profile_department, state.departmentPath, null)
        addField(R.string.profile_mobile, employee.mobile) { dial(employee.mobile) }
        addField(R.string.profile_phone, employee.phone) { dial(employee.phone) }
        addField(R.string.profile_email, employee.email) { sendEmail(employee.email) }

        // 发消息：单聊（M4）完成后跳转聊天页
        binding.sendMessage.setOnClickListener {
            Toast.makeText(this, R.string.feature_pending, Toast.LENGTH_SHORT).show()
        }
        val phone = employee.mobile.ifEmpty { employee.phone }
        binding.call.isEnabled = phone.isNotEmpty()
        binding.call.setOnClickListener { dial(phone) }
    }

    /**
     * 添加一个字段；值为空时不显示。
     *
     * @param label 标签
     * @param value 值
     * @param onClick 点击值时的操作，null 表示不可点击
     */
    private fun addField(@StringRes label: Int, value: String, onClick: (() -> Unit)?) {
        if (value.isEmpty()) return
        val field = ItemProfileFieldBinding.inflate(layoutInflater, binding.fields, true)
        field.label.setText(label)
        field.value.text = value
        if (onClick != null) {
            field.value.setTextColor(MaterialColors.getColor(field.value, androidx.appcompat.R.attr.colorPrimary))
            field.root.setOnClickListener { onClick() }
        } else {
            field.root.isClickable = false
            field.root.background = null
        }
    }

    /**
     * 打开系统拨号盘（ACTION_DIAL 不需要电话权限，由用户确认拨出）。
     *
     * @param number 电话号码
     */
    private fun dial(number: String) {
        startSafely(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }

    /**
     * 打开邮件应用。
     *
     * @param address 邮箱地址
     */
    private fun sendEmail(address: String) {
        startSafely(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))
    }

    /**
     * 启动外部应用；没有可处理的应用时提示（定制机可能没有拨号或邮件应用）。
     *
     * @param intent Intent
     */
    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.profile_no_dialer, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_JID = "jid"

        /**
         * 打开同事资料卡。
         *
         * @param context Context
         * @param jid 同事的 bare JID
         */
        fun start(context: Context, jid: String) {
            context.startActivity(Intent(context, EmployeeProfileActivity::class.java).putExtra(EXTRA_JID, jid))
        }
    }
}
