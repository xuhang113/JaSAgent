package dev.jasagent.core;

public record SchedulerConfig(int batchSize, double mergeThreshold, int queueCap) {
    public static SchedulerConfig defaults() {
        return new SchedulerConfig(4, 0.6, 8);
    }
}
