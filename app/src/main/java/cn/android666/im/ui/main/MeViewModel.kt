package cn.android666.im.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.data.avatar.AvatarRepository
import cn.android666.im.data.org.Employee
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.data.presence.MyStatusSetting
import cn.android666.im.data.presence.PresenceRepository
import cn.android666.im.ui.common.ErrorMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * "我"页面中的个人信息部分：头像（开发计划 3.21）、在线状态（3.20）。
 *
 * @property presences 在线状态
 * @property org 组织架构（本人的姓名、头像哈希）
 * @property avatars 头像
 * @param selfJid 本人的 bare JID；未登录时为 null
 */
class MeViewModel(
    private val presences: PresenceRepository,
    private val org: OrgRepository,
    private val avatars: AvatarRepository,
    selfJid: String?,
) : ViewModel() {

    private val log = Logs.file(TAG)

    /** 本人在组织架构中的信息（姓名、头像哈希）；未登录或未同步时为 null */
    val me: StateFlow<Employee?> = (selfJid?.let(org::observeEmployee) ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** 本人当前的状态设置 */
    val myStatus: StateFlow<MyStatusSetting> = presences.myStatus

    private val messageChannel = Channel<Int>(Channel.BUFFERED)

    /** 一次性提示（字符串资源 ID） */
    val messages: Flow<Int> = messageChannel.receiveAsFlow()

    /**
     * 修改本人状态。
     *
     * @param setting 新状态
     */
    fun setStatus(setting: MyStatusSetting) {
        viewModelScope.launch {
            try {
                presences.setMyStatus(setting)
                messageChannel.send(R.string.me_status_saved)
            } catch (e: XmppException) {
                // 已保存，只是没发出去；重连后的初始 presence 会带上
                log.warn("发送状态失败：{}", e.message)
                messageChannel.send(ErrorMessages.forException(e))
            }
        }
    }

    /**
     * 更换头像：处理后写入 vCard，再同步组织架构（服务器已更新本人的头像哈希），界面随之刷新。
     *
     * @param image 选择的原图数据
     */
    fun setAvatar(image: ByteArray) {
        viewModelScope.launch {
            try {
                avatars.setMyAvatar(image)
                messageChannel.send(R.string.me_avatar_saved)
            } catch (e: IOException) {
                log.warn("头像图片无法处理：{}", e.message)
                messageChannel.send(R.string.me_avatar_invalid)
                return@launch
            } catch (e: XmppException) {
                log.warn("上传头像失败：{}", e.message)
                messageChannel.send(ErrorMessages.forException(e))
                return@launch
            }
            try {
                org.sync()
            } catch (e: XmppException) {
                // 不影响头像设置结果，下次同步时更新
                log.info("更换头像后同步组织架构失败：{}", e.message)
            }
        }
    }

    private companion object {
        const val TAG = "MeViewModel"

        /** 页面不可见后多久停止收集 */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
