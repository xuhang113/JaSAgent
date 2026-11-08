package dev.jasagent.api.plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface HallucinationGuard {
    CompletionStage<Void> check(String executionId, String modelOutput);

    default CompletionStage<Boolean> grounded(String answer, String evidence, String executionId) {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }
}
