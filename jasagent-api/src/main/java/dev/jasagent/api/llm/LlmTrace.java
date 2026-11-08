package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

public interface LlmTrace {
    CompletionStage<Void> write(String executionId, String line);
}
