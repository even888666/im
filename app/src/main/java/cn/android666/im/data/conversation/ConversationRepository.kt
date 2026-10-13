package cn.android666.im.data.conversation

import androidx.room.withTransaction
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.ConversationWithPeer
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.log.Logs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 会话类型（界面据此决定名称与头像的显示方式） */
enum class ConversationKind {
    /** 单聊 */
    SINGLE,

    /** 群聊 */
    GROUP,

    /** 公司公告 */
    NOTICE,

    /** 文件传输助手（本人） */
    SELF,
}

/** 最后一条消息的发送状态（只对本人发出的消息有意义） */
enum class LastMessageState {
    /** 没有需要提示的状态（收到的消息、已发送、已送达、已读） */
    NONE,

    /** 发送中 */
    SENDING,

    /** 发送失败 */
    FAILED,
}

/**
 * 会话列表项（界面模型）。
 *
 * @property id 会话 ID
 * @property kind 会话类型
 * @property peerName 对方姓名（组织架构中有该同事时）；群聊、文件传输助手、已离职的同事为 null
 * @property avatarHash 对方头像哈希；没有时为空
 * @property preview 最后一条消息的预览文字
 * @property lastMessageState 最后一条消息的发送状态
 * @property time 最后一条消息的服务器时间（毫秒）；没有消息时为 0
 * @property unreadCount 未读数
 * @property mentionMe 是否有人 @ 我
 * @property draft 草稿
 * @property pinned 是否置顶
 * @property muted 是否免打扰
 */
data class ConversationItem(
    val id: String,
    val kind: ConversationKind,
    val peerName: String?,
    val avatarHash: String,
    val preview: String,
    val lastMessageState: LastMessageState,
    val time: Long,
    val unreadCount: Int,
    val mentionMe: Boolean,
    val draft: String,
    val pinned: Boolean,
    val muted: Boolean,
)

/**
 * 会话列表（开发计划 4.7，功能清单 §10）。
 *
 * @property databases 当前账号的用户数据库
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepository(private val databases: UserDatabaseHolder) {

    private val log = Logs.file(TAG)

    /**
     * 会话列表：置顶在前，其余按最后一条消息时间倒序。未登录时为空列表。
     *
     * @return 持续更新的列表
     */
    fun observeConversations(): Flow<List<ConversationItem>> = databases.database.flatMapLatest { db ->
        db?.conversationDao()?.observeListWithPeer()?.map { list -> list.map { it.toItem() } } ?: flowOf(emptyList())
    }

    /**
     * 总未读数（底部"消息" Tab 的角标），免打扰的会话不计入。未登录时为 0。
     *
     * @return 持续更新的未读数
     */
    fun observeTotalUnread(): Flow<Int> = databases.database.flatMapLatest { db ->
        db?.conversationDao()?.observeTotalUnread() ?: flowOf(0)
    }

    /**
     * 查询一个会话（通知判断免打扰等）。
     *
     * @param id 会话 ID
     * @return 会话；不存在或未登录时为 null
     */
    suspend fun get(id: String): ConversationItem? {
        val db = databases.database.value ?: return null
        val entity = db.conversationDao().get(id) ?: return null
        return ConversationWithPeer(entity, db.employeeDao().get(id), null, null).toItem()
    }

    /**
     * 各会话的未读数（通知在会话已读后取消）。未登录时为空。
     *
     * @return 持续更新的"会话 ID → 未读数"
     */
    fun observeUnreadCounts(): Flow<Map<String, Int>> = databases.database.flatMapLatest { db ->
        db?.conversationDao()?.observeAll()?.map { list -> list.associate { it.id to it.unreadCount } } ?: flowOf(emptyMap())
    }

    /**
     * 删除会话及其本地消息（决策记录 A86）。服务器存档中的消息不受影响。
     *
     * @param id 会话 ID
     */
    suspend fun delete(id: String) {
        val db = databases.require()
        db.withTransaction {
            db.messageDao().deleteConversation(id)
            db.conversationDao().delete(id)
        }
        log.info("删除会话：{}", id)
    }

    /**
     * 清除会话的未读数（标记已读；向对方发送已读标记见开发计划 4.9）。
     *
     * @param id 会话 ID
     */
    suspend fun clearUnread(id: String) {
        databases.require().conversationDao().clearUnread(id)
    }

    private companion object {
        const val TAG = "ConversationRepository"
    }
}

/**
 * 会话类型 → 数据库中的类型（发送消息时会话不存在需要创建）。
 *
 * @return ConversationEntity.TYPE_*
 */
fun ConversationKind.toEntityType(): Int = when (this) {
    ConversationKind.SINGLE -> ConversationEntity.TYPE_SINGLE
    ConversationKind.GROUP -> ConversationEntity.TYPE_GROUP
    ConversationKind.NOTICE -> ConversationEntity.TYPE_NOTICE
    ConversationKind.SELF -> ConversationEntity.TYPE_SELF
}

/**
 * 数据库记录 → 界面模型。
 *
 * @return 会话列表项
 */
internal fun ConversationWithPeer.toItem(): ConversationItem {
    val c = conversation
    val kind = when (c.type) {
        ConversationEntity.TYPE_GROUP -> ConversationKind.GROUP
        ConversationEntity.TYPE_NOTICE -> ConversationKind.NOTICE
        ConversationEntity.TYPE_SELF -> ConversationKind.SELF
        else -> ConversationKind.SINGLE
    }
    val state = if (lastMsgDirection == MessageEntity.DIRECTION_OUT) {
        when (lastMsgStatus) {
            MessageEntity.STATUS_SENDING -> LastMessageState.SENDING
            MessageEntity.STATUS_FAILED -> LastMessageState.FAILED
            else -> LastMessageState.NONE
        }
    } else {
        LastMessageState.NONE
    }
    return ConversationItem(
        id = c.id,
        kind = kind,
        peerName = employee?.name,
        avatarHash = employee?.avatarHash.orEmpty(),
        preview = c.lastMsgPreview,
        lastMessageState = state,
        time = c.lastMsgTime,
        unreadCount = c.unreadCount,
        mentionMe = c.mentionMe,
        draft = c.draft,
        pinned = c.pinned,
        muted = c.muted,
    )
}
