package cn.android666.im.data.presence

import cn.android666.im.core.common.Clock
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.SyncStateEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.OwnPresence
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.extension.presence.PresenceQueryItem
import cn.android666.im.core.xmpp.extension.presence.PresenceQueryRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 在线状态（开发计划 3.20，协议规范 §5.2、§5.3）：
 * - 本人状态：保存在用户数据库（sync_state 的 `presence:mine`），登录时随初始 presence 发送，修改时立即发送；
 * - 同事状态：不订阅也能按需查询（presence-query），结果缓存 60 秒，避免频繁查询。
 *
 * @property client XMPP 客户端
 * @property databases 当前账号的用户数据库
 * @property clock 时钟（缓存过期）
 */
class PresenceRepository(
    private val client: XmppClient,
    private val databases: UserDatabaseHolder,
    private val clock: Clock,
) {

    private val log = Logs.file(TAG)

    private val mutableMine = MutableStateFlow(MyStatusSetting())

    /** 本人当前的状态设置 */
    val myStatus: StateFlow<MyStatusSetting> = mutableMine.asStateFlow()

    /**
     * 查询结果缓存。
     *
     * @property presence 状态
     * @property fetchedAt 查询时的单调时间
     */
    private data class Cached(val presence: ColleaguePresence, val fetchedAt: Long)

    /** 查询结果缓存（只在 synchronized(cache) 中访问，临界区内没有挂起） */
    private val cache = HashMap<String, Cached>()

    /**
     * 读取保存的本人状态（登录后发送初始 presence 前调用）。
     *
     * @return 要发送的 presence
     */
    suspend fun loadMyPresence(): OwnPresence {
        val saved = databases.database.value?.syncStateDao()?.get(KEY_MINE)
        val setting = decode(saved)
        mutableMine.value = setting
        return setting.toOwnPresence()
    }

    /**
     * 修改本人状态：保存，已连接时立即发送（未连接时下次登录随初始 presence 发送）。
     *
     * @param setting 新状态
     */
    suspend fun setMyStatus(setting: MyStatusSetting) {
        val normalized = setting.copy(text = setting.text.trim().take(MAX_TEXT_LENGTH))
        mutableMine.value = normalized
        databases.database.value?.syncStateDao()?.put(SyncStateEntity(KEY_MINE, encode(normalized)))
        log.info("修改本人状态：{}", normalized)
        if (client.connectionState.value is ConnectionState.Authenticated) {
            client.sendPresence(normalized.toOwnPresence())
        }
    }

    /**
     * 查询同事的在线状态：60 秒内查过的直接用缓存，其余按每批 100 个查询。
     *
     * @param jids 同事的 bare JID
     * @param forceRefresh 是否忽略缓存
     * @return JID → 状态（查询失败的不包含在内）
     * @throws cn.android666.im.core.xmpp.XmppException 网络或服务器错误
     */
    suspend fun query(jids: Collection<String>, forceRefresh: Boolean = false): Map<String, ColleaguePresence> {
        val now = clock.elapsedMillis()
        val result = LinkedHashMap<String, ColleaguePresence>()
        val missing = mutableListOf<String>()
        synchronized(cache) {
            for (jid in jids.distinct()) {
                val cached = cache[jid]
                if (!forceRefresh && cached != null && now - cached.fetchedAt < CACHE_MILLIS) {
                    result[jid] = cached.presence
                } else {
                    missing += jid
                }
            }
        }
        for (chunk in missing.chunked(PresenceQueryRequest.MAX_ITEMS)) {
            val items = client.sendIq(PresenceQueryRequest(chunk))
            val fetchedAt = clock.elapsedMillis()
            synchronized(cache) {
                for (item in items) {
                    val presence = item.toModel()
                    cache[item.jid] = Cached(presence, fetchedAt)
                    result[item.jid] = presence
                }
            }
        }
        return result
    }

    /**
     * 查询一位同事的在线状态。
     *
     * @param jid bare JID
     * @param forceRefresh 是否忽略缓存
     * @return 状态；服务器没有返回时为 null
     */
    suspend fun get(jid: String, forceRefresh: Boolean = false): ColleaguePresence? = query(listOf(jid), forceRefresh)[jid]

    /** 退出登录：清空查询缓存，本人状态恢复为在线（下次登录从新账号的数据库读取） */
    fun onLogout() {
        synchronized(cache) { cache.clear() }
        mutableMine.value = MyStatusSetting()
    }

    companion object {
        private const val TAG = "PresenceRepository"

        /** sync_state 中保存本人状态的键 */
        const val KEY_MINE = "presence:mine"

        /** 查询结果的缓存时间：60 秒（协议规范 §5.3） */
        const val CACHE_MILLIS = 60_000L

        /** 自定义文字的长度上限 */
        const val MAX_TEXT_LENGTH = 50

        /**
         * 本人状态的存储格式：`状态名|文字`。
         *
         * @param setting 状态
         * @return 字符串
         */
        internal fun encode(setting: MyStatusSetting): String = "${setting.status.name}|${setting.text}"

        /**
         * 解析存储的本人状态；格式错误或没有时为在线。
         *
         * @param value 字符串
         * @return 状态
         */
        internal fun decode(value: String?): MyStatusSetting {
            if (value.isNullOrEmpty()) return MyStatusSetting()
            val status = MyStatus.entries.firstOrNull { it.name == value.substringBefore('|') } ?: MyStatus.ONLINE
            return MyStatusSetting(status, value.substringAfter('|', ""))
        }

        /**
         * 查询结果转为模型。
         *
         * @return 同事状态
         */
        private fun PresenceQueryItem.toModel() = ColleaguePresence(
            jid = jid,
            online = online,
            resources = resources.map { ResourceStatus(it.resource, statusOf(it.code, it.show), it.status) },
            lastActiveMillis = lastActiveMillis,
        )
    }
}
