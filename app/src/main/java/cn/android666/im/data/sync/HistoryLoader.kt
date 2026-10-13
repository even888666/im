package cn.android666.im.data.sync

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.MessageWithSender
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.mam.MamQueryRequest
import cn.android666.im.data.message.IncomingMessageHandler

/**
 * 聊天页向服务器加载更早的历史（开发计划 4.11，协议规范 §11.2"历史分页"）：本地消息翻到最早一条后，
 * 用 MAM 按"与对方的消息"过滤，`<before>{本地最早一条的存档 ID}</before>` 向前加载一页，写入本地库后由分页自动显示。
 *
 * 本地最早一条没有存档 ID 时，改用结束时间过滤（`end` = 它的时间）取最新一页；本地没有消息时取最新一页。
 *
 * @property pager MAM 查询
 * @property handler 消息处理（与补拉共用去重写库；历史消息不计未读、不回执、不通知）
 * @property databases 当前账号的用户数据库
 * @property ids ID 生成器（queryid）
 * @property pageSize 每页条数
 */
class HistoryLoader(
    private val pager: MamPager,
    private val handler: IncomingMessageHandler,
    private val databases: UserDatabaseHolder,
    private val ids: IdGenerator,
    private val pageSize: Int = PAGE_SIZE,
) {

    private val log = Logs.file(TAG)

    /**
     * 创建某个会话的历史加载器（交给 Paging 的 Pager 使用）。
     *
     * @param conversationId 会话 ID（对方 bare JID / 本人 bare JID）
     * @return RemoteMediator
     */
    @OptIn(ExperimentalPagingApi::class)
    fun mediator(conversationId: String): RemoteMediator<Int, MessageWithSender> = Mediator(conversationId)

    /**
     * 加载一页更早的历史。
     *
     * @param conversationId 会话 ID
     * @return 是否已经到达服务器上最早的消息
     * @throws XmppException 未连接、超时、服务器错误
     */
    suspend fun loadOlder(conversationId: String): Boolean {
        val earliest = databases.require().messageDao().earliest(conversationId)
        val request = MamQueryRequest(
            queryId = ids.uuid(),
            max = pageSize,
            with = conversationId,
            // 有存档 ID：从它之前翻页；没有：按时间过滤取它之前的最新一页；本地为空：取最新一页
            before = earliest?.stanzaId ?: "",
            end = earliest?.takeIf { it.stanzaId == null }?.serverTime,
        )
        val page = pager.query(request)
        page.items.forEach { handler.applyArchived(it, initial = true) }
        val reachedStart = page.fin.complete || page.items.isEmpty()
        log.info("加载历史：会话={}，起点={}，{} 条，到达最早={}", conversationId, earliest?.stanzaId ?: earliest?.serverTime, page.items.size, reachedStart)
        return reachedStart
    }

    /**
     * Paging 的远程加载：列表按时间倒序，APPEND 即"更早的消息"；不做 REFRESH / PREPEND（新消息由实时接收和补拉写入）。
     *
     * @property conversationId 会话 ID
     */
    @OptIn(ExperimentalPagingApi::class)
    private inner class Mediator(private val conversationId: String) : RemoteMediator<Int, MessageWithSender>() {

        /** 已到达服务器上最早的消息 */
        @Volatile
        private var exhausted = false

        /**
         * 打开页面时不从服务器刷新。
         *
         * @return 跳过初始刷新
         */
        override suspend fun initialize(): InitializeAction = InitializeAction.SKIP_INITIAL_REFRESH

        /**
         * 本地数据翻到底时加载更早的一页。
         *
         * @param loadType 加载类型
         * @param state 分页状态
         * @return 加载结果；网络错误时为 Error（界面可重试）
         */
        override suspend fun load(loadType: LoadType, state: PagingState<Int, MessageWithSender>): MediatorResult {
            if (loadType != LoadType.APPEND || exhausted) return MediatorResult.Success(endOfPaginationReached = true)
            return try {
                exhausted = loadOlder(conversationId)
                MediatorResult.Success(endOfPaginationReached = exhausted)
            } catch (e: XmppException) {
                log.warn("加载历史失败：会话={}，{}", conversationId, e.message)
                MediatorResult.Error(e)
            }
        }
    }

    private companion object {
        const val TAG = "HistoryLoader"

        /** 每页条数 */
        const val PAGE_SIZE = 50
    }
}
