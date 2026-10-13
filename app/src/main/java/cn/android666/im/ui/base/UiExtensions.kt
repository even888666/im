package cn.android666.im.ui.base

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.android666.im.App
import cn.android666.im.di.AppContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 应用级依赖容器。
 *
 * @return [App.container]
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container

/**
 * 创建 ViewModel：依赖从 [AppContainer] 中取（手动 DI，Android工程架构.md §2.1）。
 *
 * 用法：`private val viewModel by appViewModels { LoginViewModel(it.sessionManager, it.credentialStore) }`
 *
 * @param create 用容器创建 ViewModel 的函数
 * @return ViewModel 的委托，页面重建时复用同一个实例
 */
inline fun <reified VM : ViewModel> ComponentActivity.appViewModels(
    crossinline create: (AppContainer) -> VM,
): Lazy<VM> = viewModels {
    viewModelFactory { initializer { create(appContainer) } }
}

/**
 * 在页面可见（STARTED）期间收集 [flow]，不可见时自动停止，避免后台更新界面。
 *
 * @param flow 要收集的数据流
 * @param action 每个值的处理函数（主线程）
 */
fun <T> LifecycleOwner.collectWhenStarted(flow: Flow<T>, action: suspend (T) -> Unit) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { action(it) }
        }
    }
}

/**
 * 边到边显示时，给 [view] 加上系统栏（状态栏、导航栏）和输入法所占的内边距，避免内容被遮挡。
 * 内边距在 [view] 原有内边距的基础上叠加。
 *
 * @param view 一般为页面的根布局
 * @param bottom 是否处理底部（底部是 BottomNavigationView 这类自己处理导航栏的控件时传 false）
 */
fun applySystemBarsPadding(view: View, bottom: Boolean = true) {
    val left = view.paddingLeft
    val top = view.paddingTop
    val right = view.paddingRight
    val originalBottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.updatePadding(
            left = left + bars.left,
            top = top + bars.top,
            right = right + bars.right,
            bottom = if (bottom) originalBottom + bars.bottom else originalBottom,
        )
        insets
    }
}

/**
 * 跳转到 [target] 页面并清空当前任务栈（登录、退出登录、被挤下线等切换"登录态"的跳转）。
 *
 * @param target 目标 Activity
 */
fun Activity.startAsNewTask(target: Class<out Activity>) {
    startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    finish()
}

/**
 * 在 Fragment 中取所在 Activity 的 ViewModel（与 Activity 共用同一个实例）。
 * 传入 [create] 是为了 Fragment 先于 Activity 访问 ViewModel 时（例如页面重建）也能正确创建。
 *
 * @param create 用容器创建 ViewModel 的函数，应与 Activity 中的一致
 * @return ViewModel 的委托
 */
inline fun <reified VM : ViewModel> Fragment.appActivityViewModels(
    crossinline create: (AppContainer) -> VM,
): Lazy<VM> = activityViewModels {
    viewModelFactory { initializer { create(requireContext().appContainer) } }
}
