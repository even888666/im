package cn.android666.im.core.xmpp.extension.msg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 文件描述 `<file>`（协议规范 §7.1，图片、语音、视频、文件共用）。
 *
 * @property url 下载地址
 * @property name 原始文件名
 * @property size 字节数
 * @property mime MIME 类型
 * @property sha256 文件 SHA-256（可选）
 * @property width 图片 / 视频宽度（像素）
 * @property height 图片 / 视频高度（像素）
 * @property duration 语音 / 视频时长（毫秒）
 */
@Serializable
data class FileInfo(
    val url: String,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Long? = null,
)

/**
 * 缩略图 `<thumbnail>`（协议规范 §7.1，图片、视频必填）。
 *
 * @property url 下载地址
 * @property width 宽度
 * @property height 高度
 * @property mime MIME 类型
 */
@Serializable
data class ThumbnailInfo(val url: String, val width: Int, val height: Int, val mime: String)

/**
 * 合并转发中的一条消息（协议规范 §7.7）。
 *
 * @property id 原消息 ID
 * @property from 原发送方 JID
 * @property body 原消息的 body
 * @property content 原消息的内容
 * @property stamp 原消息的时间（毫秒），没有时为 null
 */
@Serializable
data class ForwardedItem(
    val id: String,
    val from: String,
    val body: String,
    val content: MessageContent,
    val stamp: Long? = null,
)

/**
 * 消息内容（协议规范 §7，`<content xmlns='urn:android666:im:msg:1' type='...'>`）。
 * 用 kotlinx.serialization 序列化后保存在消息表的 contentJson 中（类型名即协议中的 type）。
 */
@Serializable
sealed interface MessageContent {

    /** 协议中的 type */
    val type: String

    /** 文本（`content` 元素可省略）；文字在 body 中 */
    @Serializable
    @SerialName("text")
    data object Text : MessageContent {
        override val type: String get() = "text"
    }

    /**
     * 图片（§7.2）。
     *
     * @property file 原图
     * @property thumbnail 缩略图
     * @property original 是否原图（未压缩）
     */
    @Serializable
    @SerialName("image")
    data class Image(val file: FileInfo, val thumbnail: ThumbnailInfo?, val original: Boolean = false) : MessageContent {
        override val type: String get() = "image"
    }

    /**
     * 语音（§7.3）。
     *
     * @property file 语音文件（duration 为时长）
     */
    @Serializable
    @SerialName("voice")
    data class Voice(val file: FileInfo) : MessageContent {
        override val type: String get() = "voice"
    }

    /**
     * 视频（§7.4）。
     *
     * @property file 视频文件
     * @property thumbnail 封面
     */
    @Serializable
    @SerialName("video")
    data class Video(val file: FileInfo, val thumbnail: ThumbnailInfo?) : MessageContent {
        override val type: String get() = "video"
    }

    /**
     * 文件（§7.5）。
     *
     * @property file 文件
     */
    @Serializable
    @SerialName("file")
    data class File(val file: FileInfo) : MessageContent {
        override val type: String get() = "file"
    }

    /**
     * 同事名片（§7.6）。
     *
     * @property jid 同事 bare JID
     * @property name 姓名（本地组织架构中没有时兜底显示）
     */
    @Serializable
    @SerialName("card")
    data class Card(val jid: String, val name: String) : MessageContent {
        override val type: String get() = "card"
    }

    /**
     * 合并转发（§7.7）。
     *
     * @property title 标题
     * @property items 转发的消息（最多 100 条）
     */
    @Serializable
    @SerialName("merged")
    data class Merged(val title: String, val items: List<ForwardedItem>) : MessageContent {
        override val type: String get() = "merged"
    }

    /**
     * 通话记录（§7.8）。
     *
     * @property callId 通话 ID
     * @property media audio / video
     * @property result completed / canceled / rejected / missed / busy / failed
     * @property duration 通话时长（秒），仅 completed 时有值
     */
    @Serializable
    @SerialName("call")
    data class Call(val callId: String, val media: String, val result: String, val duration: Int? = null) : MessageContent {
        override val type: String get() = "call"
    }

    /**
     * 不认识的类型（协议规范 §1.5：显示 body；body 也没有时提示升级）。
     *
     * @property rawType 原始 type
     */
    @Serializable
    @SerialName("unknown")
    data class Unknown(val rawType: String) : MessageContent {
        override val type: String get() = "unknown"
    }
}
