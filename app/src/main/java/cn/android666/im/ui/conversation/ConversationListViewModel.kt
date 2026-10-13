package cn.android666.im.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.core.log.Logs
import cn.android666.im.data.conversation.ConversationItem
import cn.android666.im.data.conversation.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会话列表（开发计划 4.7）。
 *
 * @property repository 会话仓库
 */
class ConversationListViewModel(private val repository: ConversationRepository) : ViewModel() {

    private val log = Logs.file(TAG)

    /** 会话列表；null 表示还在加载（不显示空状态） */
    val conversations: StateFlow<List<ConversationItem>?> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** 等待用户确认删除的会话 ID（确认对话框在页面重建后仍在，ID 保存在 ViewModel 中） */
    var pendingDeleteId: String? = null

    /**
     * 删除会话及其本地消息。
     *
     * @param id 会话 ID
     */
    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("删除会话失败：{}", id, e)
            }
        }
    }

    /**
     * 标为已读。
     *
     * @param id 会话 ID
     */
    fun markRead(id: String) {
        viewModelScope.launch {
            try {
                repository.clearUnread(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("标为已读失败：{}", id, e)
            }
        }
    }

    private companion object {
        const val TAG = "ConversationListVM"

        /** 页面不可见超过该时间后停止查询 */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
