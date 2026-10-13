package cn.android666.im.service.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import cn.android666.im.R
import cn.android666.im.core.log.Logs

/**
 * 通知渠道（功能清单 §18.1：Android 8.0 起通知必须有渠道；消息、通话、前台服务分开）。
 * 渠道创建后用户可以在系统设置中单独调整，重复创建不会覆盖用户的设置。
 */
object NotificationChannels {

    /** 前台服务常驻通知：最低打扰（不响铃、不弹出） */
    const val SERVICE = "im_service"

    /** 新消息 */
    const val MESSAGE = "im_message"

    /** 音视频来电 */
    const val CALL = "im_call"

    /**
     * 渠道定义。
     *
     * @property id 渠道 ID
     * @property nameRes 渠道名称（显示在系统设置中）
     * @property descriptionRes 渠道说明
     * @property importance 重要性（NotificationManager.IMPORTANCE_*）
     */
    data class Definition(
        val id: String,
        @param:StringRes val nameRes: Int,
        @param:StringRes val descriptionRes: Int,
        val importance: Int,
    )

    /** 本应用的全部渠道 */
    val ALL: List<Definition> = listOf(
        Definition(SERVICE, R.string.channel_service_name, R.string.channel_service_description, NotificationManager.IMPORTANCE_MIN),
        Definition(MESSAGE, R.string.channel_message_name, R.string.channel_message_description, NotificationManager.IMPORTANCE_HIGH),
        Definition(CALL, R.string.channel_call_name, R.string.channel_call_description, NotificationManager.IMPORTANCE_HIGH),
    )

    /**
     * 创建全部渠道（Android 8.0 以下没有渠道，什么也不做）。可重复调用。
     *
     * @param context 任意 Context
     */
    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = ALL.map { def ->
            NotificationChannel(def.id, context.getString(def.nameRes), def.importance).apply {
                description = context.getString(def.descriptionRes)
                if (def.id == SERVICE) {
                    setShowBadge(false)
                }
            }
        }
        manager.createNotificationChannels(channels)
        Logs.file("NotificationChannels").info("已创建通知渠道：{}", ALL.map { it.id })
    }
}
