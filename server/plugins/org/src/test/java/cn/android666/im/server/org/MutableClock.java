package cn.android666.im.server.org;

import java.util.function.LongSupplier;

/**
 * 测试用的可调时钟（毫秒）。
 */
public final class MutableClock implements LongSupplier {

    /** 当前时间 */
    private long now;

    /**
     * 创建时钟。
     *
     * @param start 初始时间
     */
    public MutableClock(long start) {
        this.now = start;
    }

    /**
     * 当前时间。
     *
     * @return 毫秒
     */
    @Override
    public long getAsLong() {
        return now;
    }

    /**
     * 时间前进。
     *
     * @param millis 毫秒
     */
    public void advance(long millis) {
        now += millis;
    }
}
