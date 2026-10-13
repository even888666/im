package cn.android666.im.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息（Android工程架构.md §5.2）。单聊、群聊共用；按"会话 + 消息 ID"唯一（协议规范 §6.3 去重）。
 *
 * @property rowId 自增主键（本地顺序，同一服务器时间时按它排序）
 * @property msgId 消息 ID（发送方生成的 UUID，协议规范 §1.3）
 * @property conversationId 会话 ID：对方 bare JID / 群 JID / 固定 ID
 * @property stanzaId MAM 的 stanza-id（只用作补拉游标），没有时为 null
 * @property senderJid 发送方 bare JID
 * @property direction 方向：[DIRECTION_IN] 收到 / [DIRECTION_OUT] 发出
 * @property type 内容类型：text / image / voice / video / file / card / merged / call / event / custom / unknown
 * @property body 文本内容或降级文本
 * @property contentJson 非文本消息的内容（MessageContent 的 JSON），文本消息为 null
 * @property status 发送状态（发出的消息）：见 STATUS_* 常量；收到的消息为 [STATUS_RECEIVED]
 * @property errorCode 发送失败的原因（如 sensitive-word），没有时为 null
 * @property serverTime 服务器时间（毫秒），用于排序（协议规范 §1.4）
 * @property replyToMsgId 引用回复的原消息 ID
 * @property mentionsJson @ 信息（群聊，M6）
 * @property mentionMe 是否 @ 了我
 * @property retracted 是否已撤回；先收到撤回、后收到原消息时，先插入一条已撤回的占位记录
 * @property retractedBy 撤回者的 bare JID（群管理员可撤回他人消息）
 * @property voicePlayed 语音是否已听
 * @property readCount 群消息已读人数（只对自己发出的群消息有效，M6）
 * @property unreadCount 群消息未读人数（同上）
 * @property encrypted 预留端到端加密，当前恒为 false
 */
@Entity(
    tableName = "message",
    indices = [
        Index(value = ["conversationId", "msgId"], unique = true),
        Index(value = ["conversationId", "serverTime"]),
        Index(value = ["status"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val msgId: String,
    val conversationId: String,
    val stanzaId: String? = null,
    val senderJid: String,
    val direction: Int,
    val type: String,
    val body: String,
    val contentJson: String? = null,
    val status: Int,
    val errorCode: String? = null,
    val serverTime: Long,
    val replyToMsgId: String? = null,
    val mentionsJson: String? = null,
    val mentionMe: Boolean = false,
    val retracted: Boolean = false,
    val retractedBy: String? = null,
    val voicePlayed: Boolean = false,
    val readCount: Int? = null,
    val unreadCount: Int? = null,
    val encrypted: Boolean = false,
) {
    companion object {
        /** 收到的消息 */
        const val DIRECTION_IN = 0

        /** 发出的消息 */
        const val DIRECTION_OUT = 1

        /** 发送中（已写入发送队列） */
        const val STATUS_SENDING = 0

        /** 已发送（收到流管理确认） */
        const val STATUS_SENT = 1

        /** 已送达（收到对方的送达回执） */
        const val STATUS_DELIVERED = 2

        /** 已读（收到对方的已读标记） */
        const val STATUS_READ = 3

        /** 发送失败 */
        const val STATUS_FAILED = 4

        /** 收到的消息 */
        const val STATUS_RECEIVED = 10
    }
}

/**
 * 会话（Android工程架构.md §5.2）。
 *
 * @property id 会话 ID：对方 bare JID / 群 JID / 固定 ID（公司公告）；本人 bare JID 为"文件传输助手"
 * @property type 类型：见 TYPE_* 常量
 * @property lastMsgRowId 最后一条消息的 rowId
 * @property lastMsgPreview 最后一条消息的预览文字
 * @property lastMsgTime 最后一条消息的服务器时间（会话排序）
 * @property unreadCount 未读数
 * @property mentionMe 是否有人 @ 我（未读期间）
 * @property draft 草稿
 * @property pinned 是否置顶
 * @property pinnedAt 置顶时间（多个置顶会话的排序）
 * @property muted 是否免打扰
 */
@Entity(tableName = "conversation", indices = [Index(value = ["pinned", "lastMsgTime"])])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: Int,
    val lastMsgRowId: Long? = null,
    val lastMsgPreview: String = "",
    val lastMsgTime: Long = 0,
    val unreadCount: Int = 0,
    val mentionMe: Boolean = false,
    val draft: String = "",
    val pinned: Boolean = false,
    val pinnedAt: Long = 0,
    val muted: Boolean = false,
) {
    companion object {
        /** 单聊 */
        const val TYPE_SINGLE = 0

        /** 群聊 */
        const val TYPE_GROUP = 1

        /** 公司公告 */
        const val TYPE_NOTICE = 2

        /** 文件传输助手（发给本人） */
        const val TYPE_SELF = 3
    }
}

/**
 * 附件的本地状态（Android工程架构.md §5.2，M5 媒体消息使用）。
 *
 * @property id 自增主键
 * @property msgRowId 所属消息的 rowId
 * @property role 角色：[ROLE_ORIGINAL] 原文件 / [ROLE_THUMBNAIL] 缩略图
 * @property url 远程地址（上传成功后才有）
 * @property localPath 本地路径（已下载或待上传的文件）
 * @property state 状态：见 STATE_* 常量
 * @property progress 进度 0 ~ 100
 */
@Entity(tableName = "media_file", indices = [Index(value = ["msgRowId", "role"], unique = true)])
data class MediaFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val msgRowId: Long,
    val role: Int,
    val url: String? = null,
    val localPath: String? = null,
    val state: Int,
    val progress: Int = 0,
) {
    companion object {
        /** 原文件 */
        const val ROLE_ORIGINAL = 0

        /** 缩略图 */
        const val ROLE_THUMBNAIL = 1

        /** 未下载 */
        const val STATE_NOT_DOWNLOADED = 0

        /** 下载中 */
        const val STATE_DOWNLOADING = 1

        /** 已下载（或本地已有） */
        const val STATE_DONE = 2

        /** 上传中 */
        const val STATE_UPLOADING = 3

        /** 失败 */
        const val STATE_FAILED = 4
    }
}
