package dev.jasagent.api.plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SharedCallStats implements CallStats {
    private static final ConcurrentHashMap<String, Counters> COUNTS = new ConcurrentHashMap<>();

    public SharedCallStats() {
    }

    @Override
    public void recordSuccess(String toolName) {
        counters(toolName).success.incrementAndGet();
    }

    @Override
    public void recordFailure(String toolName) {
        counters(toolName).failure.incrementAndGet();
    }

    @Override
    public CallCount count(String toolName) {
        Counters counters = COUNTS.get(toolName);
        if (counters == null) {
            return new CallCount(0, 0);
        }
        return new CallCount(counters.success.get(), counters.failure.get());
    }

    private static Counters counters(String toolName) {
        return COUNTS.computeIfAbsent(toolName, key -> new Counters());
    }

    private static final class Counters {
        private final AtomicLong success = new AtomicLong();
        private final AtomicLong failure = new AtomicLong();
    }
}
