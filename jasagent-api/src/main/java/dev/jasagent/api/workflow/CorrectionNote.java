package dev.jasagent.api.workflow;

import java.time.Instant;

public record CorrectionNote(
        String noteId,
        String sessionId,
        String executionId,
        Instant createdAt,
        String source,
        String text) {
}
