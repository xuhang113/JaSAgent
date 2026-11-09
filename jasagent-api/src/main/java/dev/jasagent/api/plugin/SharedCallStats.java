package dev.jasagent.api.plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内共享的调用计数。
 * 服务加载器登记的就是这一份；所有实例读写同一张表，连续两次装载看到的是同一套次数。
 */
public final class SharedCallStats implements CallStats {
    /**
     * 按工具名或插件标识存放的进程级计数。
     * 表在类加载时建好，各个实例都不另建一份。
     */
    private static final ConcurrentHashMap<String, Counters> COUNTS = new ConcurrentHashMap<>();

    /**
     * 供服务加载器创建统计门面。
     * 构造时不新建计数表，之后的增减都写进 {@code COUNTS}。
     */
    public SharedCallStats() {
    }

    /**
     * 在共享表里把该名称的成功次数加一。
     * 名称第一次出现时从 0 开始建桶，已有次数保留。
     *
     * @param toolName 成功调用所属的工具名或插件标识
     */
    @Override
    public void recordSuccess(String toolName) {
        counters(toolName).success.incrementAndGet();
    }

    /**
     * 在共享表里把该名称的失败次数加一。
     * 名称第一次出现时从 0 开始建桶，已有次数保留。
     *
     * @param toolName 失败调用所属的工具名或插件标识
     */
    @Override
    public void recordFailure(String toolName) {
        counters(toolName).failure.incrementAndGet();
    }

    /**
     * 从共享表读出该名称的成功次数和失败次数。
     * 表里还没有这个名称时返回两路都是 0 的结果，不新建桶。
     *
     * @param toolName 要查询的工具名或插件标识
     * @return 当前累计次数
     */
    @Override
    public CallCount count(String toolName) {
        Counters counters = COUNTS.get(toolName);
        if (counters == null) {
            return new CallCount(0, 0);
        }
        return new CallCount(counters.success.get(), counters.failure.get());
    }

    /**
     * 取出该名称的计数器，没有则放进一对从 0 开始的计数。
     *
     * @param toolName 分桶使用的工具名或插件标识
     * @return 该名称的成功与失败计数器
     */
    private static Counters counters(String toolName) {
        return COUNTS.computeIfAbsent(toolName, key -> new Counters());
    }

    /**
     * 一个名称上的成功次数和失败次数。
     * 两路都用原子长整型累加，并发的记录可以同时更新。
     */
    private static final class Counters {
        private final AtomicLong success = new AtomicLong();
        private final AtomicLong failure = new AtomicLong();
    }
}
