package dev.jasagent.core;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.Map;

public final class SchedulerSettings {
    public static final String BATCH_SIZE = "jasagent.scheduler.batch-size";
    public static final String MERGE_THRESHOLD = "jasagent.scheduler.merge-threshold";
    public static final String QUEUE_CAP = "jasagent.scheduler.queue-cap";

    private final int batchSize;
    private final double mergeThreshold;
    private final int queueCap;

    private SchedulerSettings(int batchSize, double mergeThreshold, int queueCap) {
        this.batchSize = batchSize;
        this.mergeThreshold = mergeThreshold;
        this.queueCap = queueCap;
    }

    public static SchedulerSettings from(Map<String, String> configuration) {
        Map<String, String> values = configuration == null ? Map.of() : configuration;
        return new SchedulerSettings(
                positiveInt(values, BATCH_SIZE, 4),
                threshold(values, MERGE_THRESHOLD, 0.6),
                positiveInt(values, QUEUE_CAP, 8));
    }

    public int batchSize() {
        return batchSize;
    }

    public double mergeThreshold() {
        return mergeThreshold;
    }

    public int queueCap() {
        return queueCap;
    }

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
