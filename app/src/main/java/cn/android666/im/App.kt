package cn.android666.im

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cn.android666.im.core.log.Logs
import cn.android666.im.di.AppContainer

/**
 * 应用入口。只负责创建 [AppContainer]，其余初始化放在各组件中按需进行。
 *
 * 日志库不在这里初始化：它由 AndroidX Startup（[cn.android666.im.core.log.LogInitializer]）
 * 在 Application.onCreate 之前完成，以便记录更早的日志。
 */
class App : Application() {

    /** 应用级依赖容器，在 [onCreate] 中创建后不再改变 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        observeAppVisibility()
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

    private companion object {
        const val TAG = "App"
    }
}
