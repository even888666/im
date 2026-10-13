package cn.android666.im.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.android666.im.App
import cn.android666.im.R
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.data.session.SessionState
import cn.android666.im.service.notification.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 承载 XMPP 长连接的前台服务（功能清单 §3、§12：终端没有厂商推送，进程活着才能收到消息）。
 *
 * 职责只有"让进程活着"（Android工程架构.md §2.2）：连接、重连、登录都由应用级单例
 * （XmppConnectionManager、SessionManager）负责，服务只在启动时确保已经开始登录，并在常驻通知中显示连接状态。
 *
 * 前台服务类型为 specialUse（Android 14+ 必须声明类型；Android 15 的开机广播不能启动 dataSync 等类型，
 * 且 dataSync 有 6 小时时长限制，见功能清单 §18.1）。
 */
class ImService : Service() {

    private val log = Logs.file(TAG)

    /** 服务内的协程作用域，服务销毁时取消 */
    private val serviceScope = CoroutineScope(SupervisorJob())

    private val container get() = (application as App).container

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        log.info("前台服务创建")
        NotificationChannels.createAll(this)
        startInForeground(container.xmppClient.connectionState.value)
        // 连接状态变化时更新常驻通知（StateFlow 本身只在值变化时发出；状态变化不频繁，逐条记录日志）
        serviceScope.launch {
            container.xmppClient.connectionState.collect { state ->
                log.info("连接状态变化，更新常驻通知：{}", state)
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.info("前台服务启动：startId={}，来源={}", startId, intent?.getStringExtra(EXTRA_REASON) ?: "系统重启服务")
        // 开机自启、保活巡检拉起服务时还没有开始登录：有保存的凭证则自动登录
        serviceScope.launch {
            if (container.sessionManager.state.value == SessionState.LoggedOut) {
                val started = container.sessionManager.startAutoLogin()
                if (!started) {
                    log.info("没有可自动登录的凭证，停止前台服务")
                    stopSelf()
                }
            }
        }
        // 被系统杀掉后自动重建服务（intent 为 null）
        return START_STICKY
    }

    override fun onDestroy() {
        log.info("前台服务销毁")
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    /** 不提供绑定 */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 进入前台并显示常驻通知。
     *
     * @param state 当前连接状态
     */
    private fun startInForeground(state: ConnectionState) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(state), type)
    }

    /**
     * 更新常驻通知的文字。
     *
     * @param state 连接状态
     */
    private fun updateNotification(state: ConnectionState) {
        val manager = NotificationManagerCompat.from(this)
        // Android 13+ 没有通知权限时无法更新（前台服务仍正常运行），保活设置向导会引导开启
        if (manager.areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            manager.notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    /**
     * 构建常驻通知：点击打开应用。
     *
     * @param state 连接状态
     * @return 通知
     */
    private fun buildNotification(state: ConnectionState): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, NotificationChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_stat_service)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(ServiceNotificationText.forState(state)))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ImService"

        /** 常驻通知 ID */
        const val NOTIFICATION_ID = 1

        /** 启动原因，写日志用 */
        private const val EXTRA_REASON = "reason"

        /** 服务是否正在运行（同进程内判断，供保活巡检使用） */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 启动前台服务。
         *
         * Android 12+ 在后台启动前台服务受限（豁免情况：开机广播、电池优化白名单中的应用等），
         * 不满足时抛出 IllegalStateException（ForegroundServiceStartNotAllowedException），由调用方处理。
         *
         * @param context 任意 Context
         * @param reason 启动原因，写日志用
         */
        fun start(context: Context, reason: String) {
            Logs.file(TAG).info("请求启动前台服务：{}", reason)
            val intent = Intent(context, ImService::class.java).putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 停止前台服务（退出登录时）。
         *
         * @param context 任意 Context
         */
        fun stop(context: Context) {
            Logs.file(TAG).info("请求停止前台服务")
            context.stopService(Intent(context, ImService::class.java))
        }
    }
}
