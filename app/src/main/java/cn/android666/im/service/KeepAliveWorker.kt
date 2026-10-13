package cn.android666.im.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.android666.im.App
import cn.android666.im.core.log.Logs
import java.util.concurrent.TimeUnit

/**
 * 保活巡检（Android工程架构.md §2.2）：WorkManager 每 15 分钟执行一次 [KeepAliveChecker]。
 * 进程被系统杀掉后，WorkManager 到期时会重新拉起进程执行本任务，从而重启前台服务。
 *
 * @param context 应用 Context
 * @param params WorkManager 参数
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        container.keepAliveChecker.check("定期巡检")
        // 无论检查结果如何都返回成功：失败重试由下一个周期负责，避免 WorkManager 退避导致巡检间隔变长
        return Result.success()
    }

    companion object {
        /** 唯一任务名 */
        const val WORK_NAME = "im_keep_alive"

        /** 巡检间隔（分钟），WorkManager 允许的最小周期为 15 分钟 */
        const val INTERVAL_MINUTES = 15L

        /**
         * 安排周期巡检（已安排时保持原有计划，不重复安排）。
         *
         * @param context 任意 Context
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Logs.file("KeepAlive").info("已安排保活巡检，每 {} 分钟一次", INTERVAL_MINUTES)
        }
    }
}
