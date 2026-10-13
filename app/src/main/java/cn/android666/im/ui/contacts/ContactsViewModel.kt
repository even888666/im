package cn.android666.im.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.data.org.Employee
import cn.android666.im.data.org.OrgRepository
import cn.android666.im.ui.common.ErrorMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 通讯录首页（开发计划 3.16）：搜索同事、下拉刷新组织架构；常用联系人（3.19）、我的群组（M6）完成后加入。
 *
 * @property org 组织架构
 */
class ContactsViewModel(private val org: OrgRepository) : ViewModel() {

    private val log = Logs.file(TAG)

    private val query = MutableStateFlow("")

    /**
     * 搜索结果；关键字为空时为 null（显示通讯录入口）。输入停顿 [SEARCH_DEBOUNCE_MILLIS] 后才搜索，
     * 新输入会取消上一次未完成的搜索。
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<Employee>?> = query
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .mapLatest { keyword -> if (keyword.isBlank()) null else org.search(keyword) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** 是否正在同步组织架构（下拉刷新的进度圈） */
    val refreshing: StateFlow<Boolean> = org.syncing

    private val messageChannel = Channel<Int>(Channel.BUFFERED)

    /** 一次性提示（字符串资源 ID），如刷新失败的原因 */
    val messages: Flow<Int> = messageChannel.receiveAsFlow()

    /**
     * 搜索框内容变化。
     *
     * @param text 输入的文字
     */
    fun onQueryChanged(text: String) {
        query.value = text
    }

    /** 下拉刷新：同步组织架构，失败时提示原因 */
    fun refresh() {
        viewModelScope.launch {
            try {
                val result = org.sync()
                log.info("下拉刷新组织架构完成：{}", result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: XmppException) {
                log.warn("下拉刷新组织架构失败：{}", e.message)
                messageChannel.send(ErrorMessages.forException(e))
            } catch (e: IllegalStateException) {
                // 未登录（数据库未打开），一般不会出现：通讯录只在登录后显示
                log.warn("下拉刷新组织架构失败：{}", e.message)
                messageChannel.send(R.string.error_unknown)
            }
        }
    }

    companion object {
        private const val TAG = "ContactsViewModel"

        /** 搜索防抖时间 */
        const val SEARCH_DEBOUNCE_MILLIS = 200L

        /** 页面不可见后多久停止搜索结果的收集 */
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
