package dev.jasagent.api.event;

import java.time.Instant;
import java.util.Map;

public record TraceEvent(String executionId, String type, String message, Instant at, Map<String, String> fields) {
}
