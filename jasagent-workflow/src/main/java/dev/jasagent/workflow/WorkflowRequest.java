package dev.jasagent.workflow;

import java.nio.file.Path;

public record WorkflowRequest(
        String executionId,
        String sessionId,
        String userId,
        Path checkpointDatabase,
        String input,
        WorkflowDefinition workflow,
        PromptContext context,
        String jdbcUrl) {
}
