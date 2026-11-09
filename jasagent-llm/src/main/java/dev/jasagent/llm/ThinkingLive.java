package dev.jasagent.llm;

import java.util.concurrent.atomic.AtomicReference;

public final class ThinkingLive {
    private static final AtomicReference<Sink> SINK = new AtomicReference<>();

    private ThinkingLive() {
    }

    public static void bind(Sink sink) {
        SINK.set(sink);
    }

    public static void clear() {
        SINK.set(null);
    }

    public static boolean active() {
        return SINK.get() != null;
    }

    public static void delta(String executionId, String text) {
        Sink sink = SINK.get();
        if (sink == null || executionId == null || executionId.isBlank() || text == null || text.isEmpty()) {
            return;
        }
        sink.onDelta(executionId, text);
    }

    @FunctionalInterface
    public interface Sink {
        void onDelta(String executionId, String text);
    }
}
