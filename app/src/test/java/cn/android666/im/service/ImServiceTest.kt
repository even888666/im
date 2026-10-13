package cn.android666.im.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import cn.android666.im.service.notification.NotificationChannels
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** 前台服务与通知渠道测试（Robolectric） */
@RunWith(RobolectricTestRunner::class)
class ImServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun channels_created() {
        NotificationChannels.createAll(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val ids = manager.notificationChannels.map { it.id }
        assertThat(ids).containsExactly(NotificationChannels.SERVICE, NotificationChannels.MESSAGE, NotificationChannels.CALL)
        assertThat(manager.getNotificationChannel(NotificationChannels.SERVICE).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_MIN)
        assertThat(manager.getNotificationChannel(NotificationChannels.MESSAGE).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }

    /** 创建后立即进入前台，常驻通知显示连接状态（未登录） */
    @Test
    fun create_startsForegroundWithStatusNotification() {
        val controller = Robolectric.buildService(ImService::class.java).create()
        val service = controller.get()

        val notification = shadowOf(service).lastForegroundNotification
        assertThat(notification).isNotNull()
        assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(ImService.NOTIFICATION_ID)
        assertThat(notification.extras.getString(NotificationCompat.EXTRA_TEXT)).isEqualTo("未登录")
        assertThat(notification.channelId).isEqualTo(NotificationChannels.SERVICE)
        assertThat(ImService.isRunning).isTrue()

        controller.destroy()
        assertThat(ImService.isRunning).isFalse()
    }

    /** 开机自启 / 巡检拉起时没有保存的凭证：服务自行停止 */
    @Test
    fun startWithoutCredential_stopsSelf() {
        val controller = Robolectric.buildService(ImService::class.java, Intent()).create().startCommand(0, 1)
        val service = controller.get()

        // 自动登录判断在后台协程中执行（读取 DataStore），等待其完成
        val deadline = System.currentTimeMillis() + 5_000
        while (!shadowOf(service).isStoppedBySelf && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
        controller.destroy()
    }
}
