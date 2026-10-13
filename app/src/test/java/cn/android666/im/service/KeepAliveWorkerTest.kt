package cn.android666.im.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 保活巡检 Worker 与开机广播测试（Robolectric） */
@RunWith(RobolectricTestRunner::class)
class KeepAliveWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    /** 关闭测试 WorkManager 的数据库，否则 GC 时 CloseGuard 会在后续测试的输出中报"close 未调用" */
    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /** 没有保存的凭证时，巡检什么也不做，任务成功结束 */
    @Test
    fun doWork_withoutCredential_success() = runBlocking {
        val worker = TestListenableWorkerBuilder<KeepAliveWorker>(context).build()

        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    /** 安排周期巡检：重复安排只保留一个任务 */
    @Test
    fun schedule_uniquePeriodicWork() {
        KeepAliveWorker.schedule(context)
        KeepAliveWorker.schedule(context)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(KeepAliveWorker.WORK_NAME).get()
        assertThat(infos).hasSize(1)
        // 测试 WorkManager 使用同步执行器，全量运行时任务偶尔在断言前已经开始执行，两种状态都表示"已安排"
        assertThat(infos.single().state).isAnyOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)
        assertThat(infos.single().periodicityInfo!!.repeatIntervalMillis).isEqualTo(15 * 60 * 1000L)
    }

    /** 开机广播在 Manifest 中注册，接收器能处理广播 */
    @Test
    fun bootReceiver_registeredForBootCompleted() {
        val receivers = context.packageManager
            .queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName), 0)

        assertThat(receivers.map { it.activityInfo.name }).contains(BootReceiver::class.java.name)
    }
}
