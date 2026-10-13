package cn.android666.im.ui.common

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import cn.android666.im.R
import cn.android666.im.data.presence.ColleaguePresence
import cn.android666.im.data.presence.MyStatus

/**
 * 在线状态的显示文字（开发计划 3.20）。
 */
object PresenceText {

    /**
     * 状态名称。
     *
     * @param status 状态；null 表示离线
     * @return 字符串资源
     */
    @StringRes
    fun label(status: MyStatus?): Int = when (status) {
        null -> R.string.presence_offline
        MyStatus.ONLINE -> R.string.presence_online
        MyStatus.BUSY -> R.string.presence_busy
        MyStatus.MEETING -> R.string.presence_meeting
        MyStatus.BUSINESS_TRIP -> R.string.presence_business_trip
        MyStatus.AWAY -> R.string.presence_away
    }

    /**
     * 端的显示名称。
     *
     * @param resource android / pc / web
     * @return 字符串资源；不认识的端为 null（直接显示原文）
     */
    @StringRes
    fun device(resource: String): Int? = when (resource) {
        "android" -> R.string.presence_device_android
        "pc" -> R.string.presence_device_pc
        "web" -> R.string.presence_device_web
        else -> null
    }

    /**
     * 资料卡上的完整描述，如"开会中 · 手机、电脑 · 下午 3 点前在开会"或"离线 · 最后在线：2 小时前"。
     *
     * @param context Context
     * @param presence 同事状态
     * @param nowMillis 当前时间（计算"多久前"）
     * @return 描述
     */
    fun describe(context: Context, presence: ColleaguePresence, nowMillis: Long): String {
        val parts = mutableListOf(context.getString(label(presence.overall)))
        if (presence.online) {
            val devices = presence.resources.map { r -> device(r.resource)?.let(context::getString) ?: r.resource }.distinct()
            if (devices.isNotEmpty()) parts += devices.joinToString("、")
            presence.resources.firstNotNullOfOrNull { it.text?.takeIf(String::isNotBlank) }?.let { parts += it }
        } else {
            presence.lastActiveMillis?.let {
                val relative = DateUtils.getRelativeTimeSpanString(it, nowMillis, DateUtils.MINUTE_IN_MILLIS)
                parts += context.getString(R.string.presence_last_active, relative)
            }
        }
        return parts.joinToString(" · ")
    }
}
