package com.llmetor.miniagentx.utils;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简雪花 ID：timestamp(毫秒) &lt;&lt; 20 | 序号。仅要求全局唯一与趋势递增，
 * 不做严格的多 worker 位分配（单机 standalone 模式足够）。
 */
public final class SnowflakeIdGenerator {

    private static final AtomicLong SEQ = new AtomicLong(0);

    private SnowflakeIdGenerator() {
    }

    public static long nextId() {
        return (System.currentTimeMillis() << 20) | (SEQ.incrementAndGet() & 0xFFFFF);
    }

    public static String nextIdStr() {
        return Long.toString(nextId());
    }
}
