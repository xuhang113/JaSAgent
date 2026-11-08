package dev.jasagent.api.async;

import java.util.concurrent.CompletionStage;

public interface AsyncGuard {
    <T> CompletionStage<T> boundary(CompletionStage<T> stage, String executionId);
}
