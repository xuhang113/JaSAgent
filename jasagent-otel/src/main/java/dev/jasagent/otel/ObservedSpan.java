package dev.jasagent.otel;

import java.util.Map;

public record ObservedSpan(
        String name,
        String traceId,
        String spanId,
        String parentSpanId,
        Map<String, String> attributes) {
}
