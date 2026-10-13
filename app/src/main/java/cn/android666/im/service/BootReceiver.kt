package cn.android666.im.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.android666.im.App
import cn.android666.im.core.log.Logs
import kotlinx.coroutines.launch

/**
 * 开机自启（功能清单 §3）与应用升级后恢复：收到广播后执行一次保活检查，有保存的凭证时启动前台服务。
 *
 * 开机广播属于"可以在后台启动前台服务"的豁免场景；Android 15 起开机广播不能启动 dataSync 等类型的
 * 前台服务，本应用使用 specialUse 类型，不受影响（功能清单 §18.1）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val trigger = if (action == Intent.ACTION_BOOT_COMPLETED) "开机" else "应用升级"
        Logs.file(TAG).info("收到广播：{}", trigger)
        // 读取凭证是异步操作：goAsync 延长广播的处理时间（约 10 秒），完成后 finish
        val pending = goAsync()
        val container = (context.applicationContext as App).container
        container.applicationScope.launch {
            try {
                container.keepAliveChecker.check(trigger)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
