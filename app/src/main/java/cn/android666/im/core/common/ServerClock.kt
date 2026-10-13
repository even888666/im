package cn.android666.im.core.common

/**
 * 服务器时间。
 *
 * 协议规范 §1.4 规定消息时间以服务器时间为准：登录时通过 XEP-0202（Entity Time）查询服务器时间，
 * 得到"服务器时间 − 本地时间"的偏差；实时消息的时间 = 本地接收时间 + 偏差。
 * 未校准前偏差为 0，即直接使用本地时间。
 *
 * @property clock 本地时钟
 */
class ServerClock(private val clock: Clock) {

    /** 服务器时间 − 本地时间（毫秒）。可能被连接线程写、其他线程读，所以用 @Volatile */
    @Volatile
    var offsetMillis: Long = 0
        private set

    /** 是否已经用服务器时间校准过 */
    @Volatile
    var isCalibrated: Boolean = false
        private set

    /**
     * 用一次 XEP-0202 查询结果校准偏差。
     *
     * 服务器返回的时间对应请求在服务器上被处理的时刻，近似为"发出请求 + 往返耗时的一半"，
     * 所以偏差 = 服务器时间 − (本地发出时间 + 往返耗时 / 2)。
     *
     * @param serverMillis 服务器返回的时间（毫秒）
     * @param requestSentAtMillis 发出请求时的本地墙上时间（毫秒）
     * @param roundTripMillis 请求往返耗时（毫秒），小于 0 时按 0 处理
     * @return 新的偏差（毫秒）
     */
    fun calibrate(serverMillis: Long, requestSentAtMillis: Long, roundTripMillis: Long): Long {
        val halfRtt = roundTripMillis.coerceAtLeast(0) / 2
        offsetMillis = serverMillis - (requestSentAtMillis + halfRtt)
        isCalibrated = true
        return offsetMillis
    }

    /**
     * 当前的服务器时间。
     *
     * @return 估算的服务器时间（毫秒）
     */
    fun nowMillis(): Long = clock.nowMillis() + offsetMillis

    /**
     * 把本地时间换算成服务器时间。
     *
     * @param localMillis 本地墙上时间（毫秒），例如收到实时消息的时刻
     * @return 对应的服务器时间（毫秒）
     */
    fun toServerTime(localMillis: Long): Long = localMillis + offsetMillis
}
