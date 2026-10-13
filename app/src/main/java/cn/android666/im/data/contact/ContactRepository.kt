package cn.android666.im.data.contact

import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ContactEntity
import cn.android666.im.core.database.entity.ContactWithEmployee
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.RosterItem
import cn.android666.im.core.xmpp.RosterSubscription
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.data.org.Employee
import cn.android666.im.data.org.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 常用联系人（界面模型）。
 *
 * @property jid 联系人 bare JID
 * @property employee 组织架构中的员工信息；已离职或组织架构未同步时为 null
 */
data class Contact(val jid: String, val employee: Employee?)

/**
 * 常用联系人（开发计划 3.19，协议规范 §5.1）：本人花名册中订阅状态为 to / both 的条目。
 *
 * - 登录后获取完整花名册（[RosterStep]，协议规范 §2.2 第 7 步）替换本地数据；
 * - 之后的变化来自花名册推送（本端或其他端添加 / 移除），在 [scope] 中持续监听；
 * - 添加时服务器自动同意订阅，界面上没有"好友申请"。
 *
 * @property client XMPP 客户端
 * @property databases 当前账号的用户数据库
 * @property scope 应用级协程作用域（监听花名册推送）
 */
class ContactRepository(
    private val client: XmppClient,
    private val databases: UserDatabaseHolder,
    private val scope: CoroutineScope,
) {

    private val log = Logs.file(TAG)

    init {
        // 立即订阅推送，避免登录时加载花名册的推送在订阅前发出而丢失
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.rosterPushes.collect(::applyPush)
        }
    }

    /**
     * 获取完整花名册并替换本地数据。
     *
     * @return 常用联系人数量
     */
    suspend fun refresh(): Int {
        val items = client.loadRoster()
        val db = databases.database.value ?: return 0
        db.contactDao().replaceAll(items.filter { it.subscription != RosterSubscription.REMOVE }.map(::toEntity))
        val count = items.count { it.subscription.isContact }
        log.info("花名册已更新：共 {} 条，常用联系人 {} 个", items.size, count)
        return count
    }

    /**
     * 添加常用联系人（服务器自动同意，本地数据随花名册推送更新）。
     *
     * @param jid 联系人 bare JID
     */
    suspend fun add(jid: String) {
        log.info("添加常用联系人：{}", jid)
        client.addRosterItem(jid)
    }

    /**
     * 移除常用联系人。
     *
     * @param jid 联系人 bare JID
     */
    suspend fun remove(jid: String) {
        log.info("移除常用联系人：{}", jid)
        client.removeRosterItem(jid)
    }

    /**
     * 常用联系人列表（按拼音排序）。未登录时为空。
     *
     * @return 持续更新的列表
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeContacts(): Flow<List<Contact>> = databases.database.flatMapLatest { db ->
        db?.contactDao()?.observeContacts()?.map { list -> list.map(::toContact) } ?: flowOf(emptyList())
    }

    /**
     * 某人是否为常用联系人。
     *
     * @param jid bare JID
     * @return 持续更新的结果；未登录时为 false
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeIsContact(jid: String): Flow<Boolean> = databases.database.flatMapLatest { db ->
        db?.contactDao()?.observeIsContact(jid) ?: flowOf(false)
    }

    /**
     * 处理一条花名册推送；数据库未打开（未登录）时忽略。
     *
     * @param item 条目
     */
    private suspend fun applyPush(item: RosterItem) {
        val db = databases.database.value ?: return
        try {
            if (item.subscription == RosterSubscription.REMOVE) {
                db.contactDao().delete(item.jid)
            } else {
                db.contactDao().upsert(toEntity(item))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 数据库刚被关闭（退出登录）等情况，下次登录会重新获取完整花名册
            log.warn("处理花名册推送失败：{}", item, e)
        }
    }

    private companion object {
        const val TAG = "ContactRepository"

        /**
         * 花名册条目转实体。
         *
         * @param item 条目
         * @return 实体
         */
        fun toEntity(item: RosterItem) = ContactEntity(item.jid, item.subscription.name.lowercase())

        /**
         * 查询结果转界面模型。
         *
         * @param row 查询结果
         * @return 常用联系人
         */
        fun toContact(row: ContactWithEmployee) = Contact(row.jid, row.employee?.toModel())
    }
}
