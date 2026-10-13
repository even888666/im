package cn.android666.im.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.R
import cn.android666.im.core.xmpp.extension.msg.FileInfo
import cn.android666.im.core.xmpp.extension.msg.MessageContent
import cn.android666.im.core.xmpp.extension.msg.ThumbnailInfo
import cn.android666.im.data.message.ChatMessage
import cn.android666.im.data.message.LocalMedia
import cn.android666.im.data.message.MediaStatus
import cn.android666.im.data.message.MessageState
import cn.android666.im.data.message.SendError
import cn.android666.im.data.message.ReplyPreview
import cn.android666.im.ui.conversation.TimeLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** [ChatAdapter] 的显示规则测试 */
@RunWith(RobolectricTestRunner::class)
class ChatAdapterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readStateText() {
        assertThat(ChatAdapter.readStateText(MessageState.SENDING)).isNull()
        assertThat(ChatAdapter.readStateText(MessageState.FAILED)).isNull()
        assertThat(ChatAdapter.readStateText(MessageState.SENT)).isEqualTo(R.string.chat_unread)
        assertThat(ChatAdapter.readStateText(MessageState.DELIVERED)).isEqualTo(R.string.chat_unread)
        assertThat(ChatAdapter.readStateText(MessageState.READ)).isEqualTo(R.string.chat_read)
    }

    @Test
    fun errorText_onlyReasonsUserShouldKnow() {
        fun msg(state: MessageState, code: String?) = ChatMessage(
            rowId = 1, msgId = "m", conversationId = "c", outgoing = true, senderJid = "s", senderName = null,
            senderAvatarHash = "", content = MessageContent.Text, body = "x", state = state, errorCode = code,
            serverTime = 0, replyToMsgId = null, retracted = false, retractedBy = null,
        )
        assertThat(ChatAdapter.errorText(msg(MessageState.FAILED, "sensitive-word"))).isEqualTo(R.string.chat_error_sensitive)
        assertThat(ChatAdapter.errorText(msg(MessageState.FAILED, "timeout"))).isNull()
        assertThat(ChatAdapter.errorText(msg(MessageState.SENT, "sensitive-word"))).isNull()
        // 附件上传相关（开发计划 5.4）
        assertThat(ChatAdapter.errorText(msg(MessageState.FAILED, SendError.UPLOAD_FAILED))).isEqualTo(R.string.chat_error_upload)
        assertThat(ChatAdapter.errorText(msg(MessageState.FAILED, SendError.FILE_TOO_LARGE))).isEqualTo(R.string.chat_error_file_too_large)
        assertThat(ChatAdapter.errorText(msg(MessageState.FAILED, SendError.FILE_MISSING))).isEqualTo(R.string.chat_error_file_missing)
    }

    /** 图片显示的内容：本地缩略图 → 缩略图地址 → 原图地址 → 本地原图 */
    @Test
    fun imageModel_order() {
        val localThumb = File.createTempFile("thumb", ".jpg")
        val localMain = File.createTempFile("main", ".jpg")
        val withUrls = MessageContent.Image(
            FileInfo("https://h/main.jpg", "a.jpg", 1, "image/jpeg"),
            ThumbnailInfo("https://h/thumb.jpg", 10, 10, "image/jpeg"),
        )
        val noUrls = MessageContent.Image(FileInfo("", "a.jpg", 1, "image/jpeg"), ThumbnailInfo("", 10, 10, "image/jpeg"))
        fun msg(content: MessageContent.Image, media: LocalMedia?) = ChatMessage(
            rowId = 1, msgId = "m", conversationId = "c", outgoing = true, senderJid = "s", senderName = null,
            senderAvatarHash = "", content = content, body = "[图片]", state = MessageState.SENT, errorCode = null,
            serverTime = 0, replyToMsgId = null, retracted = false, retractedBy = null, media = media,
        )
        fun media(thumb: String?, main: String?) = LocalMedia(thumb, main, MediaStatus.DONE, progress = 0)

        assertThat(ChatAdapter.imageModel(msg(withUrls, media(localThumb.path, null)), withUrls)).isEqualTo(localThumb)
        // 本地缩略图被清理：用地址
        assertThat(ChatAdapter.imageModel(msg(withUrls, media("/none/x.jpg", null)), withUrls)).isEqualTo("https://h/thumb.jpg")
        assertThat(ChatAdapter.imageModel(msg(withUrls, null), withUrls)).isEqualTo("https://h/thumb.jpg")
        val noThumb = withUrls.copy(thumbnail = null)
        assertThat(ChatAdapter.imageModel(msg(noThumb, null), noThumb)).isEqualTo("https://h/main.jpg")
        assertThat(ChatAdapter.imageModel(msg(noUrls, media(null, localMain.path)), noUrls)).isEqualTo(localMain)
        assertThat(ChatAdapter.imageModel(msg(noUrls, null), noUrls)).isNull()
        localThumb.delete()
        localMain.delete()
    }

    /** 文件消息的状态文字 */
    @Test
    fun fileStatusText() {
        val info = FileInfo("https://h/a.pdf", "a.pdf", 1_258_291, "application/pdf")
        val local = File.createTempFile("file", ".pdf")
        fun msg(outgoing: Boolean, media: LocalMedia?) = ChatMessage(
            rowId = 1, msgId = "m", conversationId = "c", outgoing = outgoing, senderJid = "s", senderName = null,
            senderAvatarHash = "", content = MessageContent.File(info), body = "[文件] a.pdf", state = MessageState.SENT, errorCode = null,
            serverTime = 0, replyToMsgId = null, retracted = false, retractedBy = null, media = media,
        )
        fun media(status: MediaStatus, progress: Int = 0, path: String? = null) = LocalMedia(null, path, status, progress)
        fun text(outgoing: Boolean, media: LocalMedia?) = ChatAdapter.fileStatusText(context, msg(outgoing, media), info)

        assertThat(text(false, null)).isEqualTo("1.2 MB · 未下载")
        assertThat(text(false, media(MediaStatus.DOWNLOADING, 40))).isEqualTo("1.2 MB · 下载中 40%")
        assertThat(text(false, media(MediaStatus.NOT_DOWNLOADED, 35))).isEqualTo("1.2 MB · 已暂停 35%，点击继续")
        assertThat(text(false, media(MediaStatus.DONE, 100, local.path))).isEqualTo("1.2 MB · 已下载")
        // 已下载的文件被删除
        assertThat(text(false, media(MediaStatus.DONE, 100, "/none/a.pdf"))).isEqualTo("1.2 MB · 未下载")
        assertThat(text(false, media(MediaStatus.EXPIRED))).isEqualTo("1.2 MB · 文件已过期或已被清理")
        assertThat(text(false, media(MediaStatus.FAILED))).isEqualTo("1.2 MB · 下载失败，点击重试")
        assertThat(text(true, media(MediaStatus.UPLOADING, 20, local.path))).isEqualTo("1.2 MB · 上传中 20%")
        assertThat(text(true, media(MediaStatus.DONE, 100, local.path))).isEqualTo("1.2 MB")
        val notUploaded = info.copy(url = "")
        assertThat(ChatAdapter.fileStatusText(context, msg(true, media(MediaStatus.FAILED)), notUploaded)).isEqualTo("1.2 MB · 上传失败")
        local.delete()
    }

    @Test
    fun timeHeaderText() {
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.Today(9, 5), 9, 5))).isEqualTo("09:05")
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.Yesterday, 23, 0))).isEqualTo("昨天 23:00")
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.Weekday(1), 8, 30))).isEqualTo("星期一 08:30")
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.ThisYear(10, 6), 8, 30))).isEqualTo("10月6日 08:30")
        assertThat(ChatAdapter.timeHeaderText(context, ChatTimeLabel(TimeLabel.Older(2025, 1, 2), 8, 30)))
            .isEqualTo("2025年1月2日 08:30")
    }

    @Test
    fun quoteText() {
        val self = "10001@im.example.com"
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Found("10002@im.example.com", "李四", "原文"), self)).isEqualTo("李四：原文")
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Found("10002@im.example.com", null, "原文"), self)).isEqualTo("10002：原文")
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Found(self, "张三", "我说的"), self)).isEqualTo("我：我说的")
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Retracted, self)).isEqualTo("原消息已撤回")
        assertThat(ChatAdapter.quoteText(context, ReplyPreview.Missing, self)).isEqualTo("原消息不可见")
    }
}
