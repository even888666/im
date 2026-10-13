package cn.android666.im.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.data.contact.ContactRepository
import cn.android666.im.data.org.Employee
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.data.presence.ColleaguePresence
import cn.android666.im.data.presence.PresenceRepository
import cn.android666.im.ui.common.ErrorMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 同事资料卡的内容。
 *
 * @property employee 员工；为 null 时表示不在组织架构中（已离职或未同步）
 * @property departmentPath 部门完整路径
 * @property self 是否为本人（本人不显示"发消息""打电话""常用联系人"）
 * @property contact 是否为常用联系人
 */
data class ProfileUiState(val employee: Employee?, val departmentPath: String, val self: Boolean, val contact: Boolean)

/**
 * 同事资料卡（开发计划 3.18 ~ 3.20）：员工信息、在线状态、发消息、打电话、添加 / 移除常用联系人。
 *
 * @property org 组织架构
 * @property contacts 常用联系人
 * @property presences 在线状态
 * @property jid 同事的 bare JID
 * @property selfJid 本人的 bare JID（未登录时为 null）
 */
class EmployeeProfileViewModel(
    private val org: OrgRepository,
    private val contacts: ContactRepository,
    private val presences: PresenceRepository,
    private val jid: String,
    private val selfJid: String?,
) : ViewModel() {

    private val log = Logs.file(TAG)

    private val mutablePresence = MutableStateFlow<ColleaguePresence?>(null)

    /** 在线状态（协议规范 §5.3，缓存 60 秒）；还没查到或查询失败时为 null */
    val presence: StateFlow<ColleaguePresence?> = mutablePresence.asStateFlow()

    init {
        refreshPresence()
    }

    /** 查询在线状态（打开资料卡、回到页面时调用；60 秒内使用缓存） */
    fun refreshPresence() {
        viewModelScope.launch {
            try {
                mutablePresence.value = presences.get(jid)
            } catch (e: XmppException) {
                // 断网等情况不显示在线状态，不提示
                log.info("查询在线状态失败：{}", e.message)
            }
        }
    }

    /** 资料卡内容；还没读出时为 null */
    val state: StateFlow<ProfileUiState?> = combine(org.observeEmployee(jid), contacts.observeIsContact(jid)) { employee, isContact ->
        ProfileUiState(
            employee = employee,
            departmentPath = employee?.let { org.departmentPath(it.deptId) }.orEmpty(),
            self = jid == selfJid,
            contact = isContact,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val messageChannel = Channel<Int>(Channel.BUFFERED)

    /** 一次性提示（字符串资源 ID） */
    val messages: Flow<Int> = messageChannel.receiveAsFlow()

    /** 添加或移除常用联系人（按当前状态切换），结果通过 [state] 更新，失败时提示原因 */
    fun toggleContact() {
        val current = state.value ?: return
        viewModelScope.launch {
            try {
                if (current.contact) contacts.remove(jid) else contacts.add(jid)
                messageChannel.send(if (current.contact) R.string.profile_contact_removed else R.string.profile_contact_added)
            } catch (e: XmppException) {
                log.warn("修改常用联系人失败：{}", e.message)
                messageChannel.send(ErrorMessages.forException(e))
            }
        }
    }

    private companion object {
        const val TAG = "EmployeeProfile"

        /** 页面不可见后多久停止收集 */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
