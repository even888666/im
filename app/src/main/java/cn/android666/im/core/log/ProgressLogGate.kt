package cn.android666.im.core.log

/**
 * 进度日志节流：只在开始（0%）、每 [stepPercent] 的节点以及完成（100%）时允许打印，
 * 避免上传 / 下载进度逐条刷屏（见 Android工程架构.md §8"进度类日志"）。
 *
 * 每个传输任务使用一个实例。进度跳跃时（如从 10% 直接到 60%）只打印一次。
 * 失败日志不经过本类，由调用方直接打印。
 *
 * @property stepPercent 打印间隔（百分比），默认 25，即 0 / 25 / 50 / 75 / 100
 */
class ProgressLogGate(private val stepPercent: Int = 25) {

    init {
        require(stepPercent in 1..100) { "stepPercent 必须在 1..100 之间：$stepPercent" }
    }

    /** 上一次打印的节点序号（进度 / stepPercent），-1 表示还没打印过 */
    private var lastBucket = -1

    /**
     * 判断本次进度是否需要打印。
     *
     * @param percent 当前进度（百分比），超出 0..100 时按边界处理
     * @return 进入新节点时返回 true（同一节点只返回一次 true）
     */
    @Synchronized
    fun shouldLog(percent: Int): Boolean {
        val bucket = percent.coerceIn(0, 100) / stepPercent
        if (bucket <= lastBucket) return false
        lastBucket = bucket
        return true
    }

    /**
     * 按已传输字节数和总字节数判断是否需要打印。
     *
     * @param transferred 已传输字节数
     * @param total 总字节数；未知（小于等于 0）时只在开始时打印一次
     * @return 进入新节点时返回 true
     */
    fun shouldLog(transferred: Long, total: Long): Boolean {
        if (total <= 0) return shouldLog(0)
        return shouldLog((transferred * 100 / total).toInt())
    }
}
