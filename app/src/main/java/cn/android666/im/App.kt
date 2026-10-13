package cn.android666.im

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.work.Configuration
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cn.android666.im.core.log.Logs
import cn.android666.im.data.session.SessionState
import cn.android666.im.di.AppContainer
import cn.android666.im.service.ImService
import cn.android666.im.service.KeepAliveWorker
import kotlinx.coroutines.launch

/**
 * 应用入口。只负责创建 [AppContainer]，其余初始化放在各组件中按需进行。
 *
 * 日志库不在这里初始化：它由 AndroidX Startup（[cn.android666.im.core.log.LogInitializer]）
 * 在 Application.onCreate 之前完成，以便记录更早的日志。
 */
class App : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    /**
     * Coil 的全局图片加载器：网络图片使用全局 OkHttpClient（只信任企业根证书，开发计划 5.1）。
     *
     * @param context Context
     * @return 图片加载器
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttpClient })) }
        .build()


    /**
     * WorkManager 按需初始化的配置（Manifest 中已移除默认的 WorkManagerInitializer）：
     * 第一次调用 WorkManager.getInstance 时用这里的配置初始化。这样不依赖 AndroidX Startup 的执行时机，
     * Robolectric 测试中也能正常使用 WorkManager。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()


    /** 应用级依赖容器，在 [onCreate] 中创建后不再改变 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        observeAppVisibility()
        bindServiceToSession()
        KeepAliveWorker.schedule(this)
        Logs.file(TAG).info(
            "应用启动：versionName={}，versionCode={}，debug={}",
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.DEBUG,
        )
    }

    /**
     * 观察整个进程的前后台切换（所有 Activity 都不可见时为后台），转给 AppVisibilityHandler 处理。
     * ProcessLifecycleOwner 的回调在主线程，处理器内部切到后台协程执行网络操作。
     */
    private fun observeAppVisibility() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.appVisibilityHandler.onForeground()

            override fun onStop(owner: LifecycleOwner) = container.appVisibilityHandler.onBackground()
        })
    }

    /**
     * 前台服务随会话启停：登录中、已登录、强制改密时保持运行（承载长连接）；未登录、会话结束时停止。
     * 数据层不能依赖 service 层（Android工程架构.md §3.3），所以由 App 负责二者的联动。
     */
    private fun bindServiceToSession() {
        container.applicationScope.launch {
            container.sessionManager.state.collect { state ->
                when (state) {
                    is SessionState.Starting, is SessionState.Ready, is SessionState.MustChangePassword ->
                        if (!ImService.isRunning) startServiceSafely(state)
                    SessionState.LoggedOut, is SessionState.Ended ->
                        if (ImService.isRunning) ImService.stop(this@App)
                }
            }
        }
    }

    /**
     * 启动前台服务。Android 12+ 在后台启动前台服务受限，抛出异常时只记录日志：
     * 服务被系统重建（START_STICKY）或保活巡检会再次尝试。
     *
     * @param state 触发启动的会话状态，写日志用
     */
    private fun startServiceSafely(state: SessionState) {
        try {
            ImService.start(this, "会话状态 ${state::class.simpleName}")
        } catch (e: IllegalStateException) {
            Logs.file(TAG).warn("无法启动前台服务（应用在后台受系统限制）", e)
        }
    }

    private companion object {
        const val TAG = "App"
    }
}
