package dev.jasagent.api.trace;

import java.util.Map;

public record TraceEvent(String type, String message, Map<String, String> fields) {
    public TraceEvent {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
