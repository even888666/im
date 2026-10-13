package cn.android666.im.data.sync

import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.SyncStateEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.mam.MamQueryRequest
import cn.android666.im.core.xmpp.extension.msg.MessageCodec
import cn.android666.im.data.message.IncomingMessageHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 单聊消息补拉（开发计划 4.10，协议规范 §11.2）：查询本人归档，从上次保存的游标之后按页拉取，直到 complete。
 *
 * - 游标：本人归档最后一条的存档 ID，保存在 sync_state 的 `mam:self`，每页处理完后保存；
 * - 游标缺失：本地有消息时，从"本地最后一条消息时间 − 5 分钟"开始按时间过滤（依赖去重）；
 *   本地没有消息（首次登录）时只拉取最近 7 天，且不计未读（决策记录 A98）；
 * - 游标失效（服务器返回 item-not-found，例如存档被清理）：清除游标，改用时间过滤重新拉取；
 * - 结果按时间顺序交给 [IncomingMessageHandler.applyArchived]：保存消息、应用已读；不回执、不通知；
 * - 单轮最多 [maxPages] 页，防止异常情况下无限循环，剩余的下次登录 / 重连时继续。
 *
 * @property pager MAM 查询
 * @property handler 消息处理（补拉的消息与实时消息共用去重和写库逻辑）
 * @property databases 当前账号的用户数据库
 * @property serverClock 服务器时间（首次登录的 7 天范围）
 * @property ids ID 生成器（queryid）
 * @property pageSize 每页条数
 * @property maxPages 单轮最多页数
 */
class MamSyncer(
    private val pager: MamPager,
    private val handler: IncomingMessageHandler,
    private val databases: UserDatabaseHolder,
    private val serverClock: ServerClock,
    private val ids: IdGenerator,
    private val pageSize: Int = MamQueryRequest.DEFAULT_MAX,
    private val maxPages: Int = MAX_PAGES,
) {

    private val log = Logs.file(TAG)

    /** 同一时间只有一轮补拉 */
    private val mutex = Mutex()

    private val syncingFlow = MutableStateFlow(false)

    /** 是否正在补拉（主界面状态条显示"收取中…"） */
    val syncing: StateFlow<Boolean> = syncingFlow.asStateFlow()

    /**
     * 补拉本人归档。
     *
     * @return 本轮处理的消息数
     * @throws XmppException 连接类错误向上抛出（重连后重新补拉）；游标失效以外的服务器错误也向上抛出
     */
    suspend fun syncSelf(): Int = mutex.withLock {
        syncingFlow.value = true
        try {
            doSync()
        } finally {
            syncingFlow.value = false
        }
    }

    /**
     * 执行补拉。
     *
     * @return 处理的消息数
     */
    private suspend fun doSync(): Int {
        val db = databases.require()
        val state = db.syncStateDao()
        var cursor = state.get(KEY_SELF)
        var initial = false
        var start: Long? = null
        if (cursor == null) {
            val latest = db.messageDao().maxServerTime()
            if (latest == null) {
                initial = true
                start = serverClock.nowMillis() - INITIAL_RANGE_MILLIS
            } else {
                start = latest - OVERLAP_MILLIS
            }
        }
        log.info("开始补拉本人归档：游标={}，起始时间={}，首次={}", cursor, start?.let(MessageCodec::formatTime), initial)
        var total = 0
        var pages = 0
        var retriedWithoutCursor = false
        while (pages < maxPages) {
            val request = MamQueryRequest(queryId = ids.uuid(), max = pageSize, after = cursor, start = start)
            val page = try {
                pager.query(request)
            } catch (e: XmppException.StanzaError) {
                if (e.condition != "item-not-found" || cursor == null || retriedWithoutCursor) throw e
                // 游标在服务器上已不存在：改用时间过滤
                log.warn("补拉游标失效，改用时间过滤：{}", cursor)
                state.delete(KEY_SELF)
                cursor = null
                retriedWithoutCursor = true
                start = (db.messageDao().maxServerTime() ?: serverClock.nowMillis()) - OVERLAP_MILLIS
                continue
            }
            page.items.forEach { handler.applyArchived(it, initial) }
            total += page.items.size
            pages++
            page.fin.rsm?.last?.let {
                cursor = it
                state.put(SyncStateEntity(KEY_SELF, it))
            }
            if (page.fin.complete || page.items.isEmpty()) break
        }
        if (pages >= maxPages) log.warn("补拉达到单轮上限 {} 页，剩余的下次继续", maxPages)
        log.info("补拉完成：{} 页，{} 条，游标={}", pages, total, cursor)
        return total
    }

    companion object {
        private const val TAG = "MamSyncer"

        /** 本人归档的游标名 */
        const val KEY_SELF = "mam:self"

        /** 首次登录拉取的时间范围：7 天（协议规范 §11.2） */
        const val INITIAL_RANGE_MILLIS = 7 * 24 * 3600 * 1000L

        /** 按时间过滤时往前多拉的时间：5 分钟（协议规范 §11.2） */
        const val OVERLAP_MILLIS = 5 * 60 * 1000L

        /** 单轮最多页数 */
        const val MAX_PAGES = 50
    }
}
