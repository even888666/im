package cn.android666.im.di

import android.content.Context
import cn.android666.im.core.common.Clock
import cn.android666.im.core.common.DefaultDispatcherProvider
import cn.android666.im.core.common.DispatcherProvider
import cn.android666.im.core.common.IdGenerator
import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.common.SystemTimeClock
import cn.android666.im.core.common.UuidIdGenerator
import cn.android666.im.core.log.LogFilePrefixManager
import cn.android666.im.core.log.LogFilePrefixStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级依赖容器，在 [cn.android666.im.App.onCreate] 中创建，持有所有单例（见 Android工程架构.md §4.1）。
 *
 * 项目采用手动依赖注入（不用 Hilt，见 Android工程架构.md §11）：所有类的依赖都从构造函数传入，
 * 由本容器负责组装；测试时直接构造被测类并传入假实现，不需要本容器。
 *
 * 新增单例时：无需立即创建的用 `by lazy`，避免拖慢冷启动。
 *
 * @param context 任意 Context，内部只保存 applicationContext，避免泄漏 Activity
 */
class AppContainer(context: Context) {

    /** 应用级 Context */
    val appContext: Context = context.applicationContext

    /** 协程调度器 */
    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()

    /**
     * 应用级协程作用域，用于生命周期与进程一致的长期任务（连接管理、同步等）。
     * 使用 SupervisorJob：某个子任务失败不会取消其他任务。禁止使用 GlobalScope（Android工程架构.md §10）。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** 本地时钟 */
    val clock: Clock = SystemTimeClock

    /** 服务器时间（登录后用 XEP-0202 校准） */
    val serverClock: ServerClock = ServerClock(clock)

    /** 协议 ID 生成器 */
    val idGenerator: IdGenerator = UuidIdGenerator()

    /** 日志文件名前缀管理：登录成功后切换为工号 */
    val logFilePrefixManager: LogFilePrefixManager by lazy {
        LogFilePrefixManager(LogFilePrefixStore.create(appContext))
    }
}
