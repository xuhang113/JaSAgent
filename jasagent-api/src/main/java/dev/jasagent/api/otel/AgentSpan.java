package dev.jasagent.api.otel;

public interface AgentSpan {
    String name();

    String traceId();

    String spanId();

    String attribute(String key);
}
