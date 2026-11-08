package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

public interface LlmCallSink {
    CompletionStage<Void> record(LlmCallRecord record);
}
