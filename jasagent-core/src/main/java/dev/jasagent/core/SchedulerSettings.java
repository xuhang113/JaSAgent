package dev.jasagent.core;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.Map;

/**
 * 调度器每批取出的数量、合并相关度门槛和每个子代理的未完成上限。
 */
public final class SchedulerSettings {
    /**
     * 每轮从主队列取出的待办数量配置键，缺省为 4。
     */
    public static final String BATCH_SIZE = "jasagent.scheduler.batch-size";
    /**
     * 两条待办可以合并到同一子代理的最低相关度配置键，缺省为 0.6。
     */
    public static final String MERGE_THRESHOLD = "jasagent.scheduler.merge-threshold";
    /**
     * 一个子代理尚未结束的待办上限配置键，缺省为 8。
     */
    public static final String QUEUE_CAP = "jasagent.scheduler.queue-cap";

    private final int batchSize;
    private final double mergeThreshold;
    private final int queueCap;

    /**
     * 保存已经校验过的三项调度参数。
     *
     * @param batchSize 每轮取出的待办数，至少为 1
     * @param mergeThreshold 合并门槛，范围是 0 到 1
     * @param queueCap 单个子代理的未完成上限，至少为 1
     */
    private SchedulerSettings(int batchSize, double mergeThreshold, int queueCap) {
        this.batchSize = batchSize;
        this.mergeThreshold = mergeThreshold;
        this.queueCap = queueCap;
    }

    /**
     * 从配置读取三项参数。缺省时使用 4、0.6 和 8；写了但不是合法正整数或零到一的小数时失败。
     *
     * @param configuration 调度配置；空则全部用缺省
     * @return 可供队列调度器使用的参数
     */
    public static SchedulerSettings from(Map<String, String> configuration) {
        Map<String, String> values = configuration == null ? Map.of() : configuration;
        return new SchedulerSettings(
                positiveInt(values, BATCH_SIZE, 4),
                threshold(values, MERGE_THRESHOLD, 0.6),
                positiveInt(values, QUEUE_CAP, 8));
    }

    /**
     * 返回每轮最多从主队列取出的待办数。
     *
     * @return 批大小
     */
    public int batchSize() {
        return batchSize;
    }

    /**
     * 返回合并到同一子代理所需的最低相关度。
     *
     * @return 零到一之间的门槛
     */
    public double mergeThreshold() {
        return mergeThreshold;
    }

    /**
     * 返回一个子代理允许积压的未完成待办数。
     *
     * @return 队列上限
     */
    public int queueCap() {
        return queueCap;
    }

    /**
     * 读取正整数配置。键不存在时用缺省；空白、非整数或小于 1 时失败。
     *
     * @param values 配置表
     * @param key 要读取的键
     * @param fallback 键不存在时使用的缺省值
     * @return 解析出的正整数
     */
    private static int positiveInt(Map<String, String> values, String key, int fallback) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, key);
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1) {
                throw new AgentException(ErrorCode.INTERNAL, key);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new AgentException(ErrorCode.INTERNAL, key, null, ex);
        }
    }

    /**
     * 读取零到一之间的小数门槛。键不存在时用缺省；空白、非数字或越界时失败。
     *
     * @param values 配置表
     * @param key 要读取的键
     * @param fallback 键不存在时使用的缺省值
     * @return 解析出的门槛
     */
    private static double threshold(Map<String, String> values, String key, double fallback) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, key);
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (Double.isNaN(parsed) || parsed < 0.0 || parsed > 1.0) {
                throw new AgentException(ErrorCode.INTERNAL, key);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new AgentException(ErrorCode.INTERNAL, key, null, ex);
        }
    }
}
