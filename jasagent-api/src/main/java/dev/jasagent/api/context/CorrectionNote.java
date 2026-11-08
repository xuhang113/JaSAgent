package dev.jasagent.api.context;

import java.time.Instant;

public record CorrectionNote(String noteId, String sessionId, String executionId, Instant createdAt, CorrectionSource source, String text) {
}
