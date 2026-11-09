package dev.jasagent.api.llm;

import java.util.List;

public record LlmChatRequest(
        String executionId,
        LlmTask task,
        String provider,
        List<LlmMessage> messages,
        int remainingSteps,
        String model) {

    public LlmChatRequest(String executionId, LlmTask task, String provider, List<LlmMessage> messages, int remainingSteps) {
        this(executionId, task, provider, messages, remainingSteps, null);
    }
}
