package cn.android666.im.ui.forward

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.data.conversation.ConversationKind
import cn.android666.im.data.conversation.ConversationRepository
import cn.android666.im.data.org.OrgRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 转发的目标。
 *
 * @property id 会话 ID（同事 / 本人 bare JID）
 * @property kind 会话类型
 * @property name 名称；文件传输助手为 null（界面显示固定名称）
 * @property avatarHash 头像哈希
 */
data class ForwardTarget(val id: String, val kind: ConversationKind, val name: String?, val avatarHash: String)

/**
 * 选择转发对象（开发计划 4.16）：没有搜索时显示"文件传输助手"和最近的单聊会话；输入关键字时搜索同事。
 * 群聊在 M6 支持后加入。
 *
 * @param conversations 会话列表
 * @property org 组织架构（搜索同事）
 * @property selfJid 本人 bare JID（文件传输助手）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardTargetViewModel(
    conversations: ConversationRepository,
    private val org: OrgRepository,
    private val selfJid: String?,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** 最近的会话（单聊）与文件传输助手 */
    private val recent: Flow<List<ForwardTarget>> = conversations.observeConversations().map { list ->
        val self = listOfNotNull(selfJid?.let { ForwardTarget(it, ConversationKind.SELF, null, "") })
        self + list.filter { it.kind == ConversationKind.SINGLE && it.id != selfJid }
            .map { ForwardTarget(it.id, it.kind, it.peerName ?: it.id.substringBefore('@'), it.avatarHash) }
    }

    /** 当前显示的目标 */
    val targets: StateFlow<List<ForwardTarget>> = query
        .flatMapLatest { keyword ->
            if (keyword.isBlank()) {
                recent
            } else {
                flow {
                    emit(
                        org.search(keyword.trim()).map {
                            if (it.jid == selfJid) {
                                ForwardTarget(it.jid, ConversationKind.SELF, null, it.avatarHash)
                            } else {
                                ForwardTarget(it.jid, ConversationKind.SINGLE, it.name, it.avatarHash)
                            }
                        },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * 搜索关键字变化。
     *
     * @param keyword 关键字
     */
    fun onQueryChanged(keyword: String) {
        query.value = keyword
    }

    /** 等待确认的目标（确认框在页面重建后仍在） */
    var pending: ForwardTarget? = null

    private companion object {
        /** 页面不可见超过该时间后停止查询 */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
