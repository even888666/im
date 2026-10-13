package cn.android666.im.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 协程调度器提供者。所有类通过构造函数注入它，而不是直接引用 [Dispatchers]，
 * 测试时可替换为 `TestDispatcher`，从而控制执行顺序和虚拟时间（见 Android工程架构.md §2.1）。
 */
interface DispatcherProvider {
    /** IO 调度器：数据库、网络、文件操作 */
    val io: CoroutineDispatcher

    /** 计算调度器：CPU 密集型任务（图片压缩、大列表排序等） */
    val default: CoroutineDispatcher

    /** 主线程调度器：更新 UI */
    val main: CoroutineDispatcher
}

/** 生产环境使用的调度器，直接对应 [Dispatchers] 中的同名调度器 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
}
