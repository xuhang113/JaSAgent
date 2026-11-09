package dev.jasagent.api.llm;

import java.util.List;

public record LlmChatRequest(
        String executionId,
        LlmTask task,
        String provider,
        List<LlmMessage> messages,
        int remainingSteps,
        String model,
        List<ToolDefinition> tools) {

    public LlmChatRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public LlmChatRequest(
            String executionId,
            LlmTask task,
            String provider,
            List<LlmMessage> messages,
            int remainingSteps,
            String model) {
        this(executionId, task, provider, messages, remainingSteps, model, List.of());
    }

    public LlmChatRequest(String executionId, LlmTask task, String provider, List<LlmMessage> messages, int remainingSteps) {
        this(executionId, task, provider, messages, remainingSteps, null, List.of());
    }
}
