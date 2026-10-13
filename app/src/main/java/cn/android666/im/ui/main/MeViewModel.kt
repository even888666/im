package cn.android666.im.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.data.presence.MyStatusSetting
import cn.android666.im.data.presence.PresenceRepository
import cn.android666.im.ui.common.ErrorMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * "我"页面中与本人状态相关的部分（开发计划 3.20）：显示和修改在线状态。
 *
 * @property presences 在线状态
 */
class MeViewModel(private val presences: PresenceRepository) : ViewModel() {

    private val log = Logs.file(TAG)

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

    private companion object {
        const val TAG = "MeViewModel"
    }
}
