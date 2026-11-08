package dev.jasagent.api.pass;

import java.nio.file.Path;

public record PassApproval(
        String userId,
        String executionId,
        String passId,
        PassDecision decision,
        Path userDirectory) {
}
