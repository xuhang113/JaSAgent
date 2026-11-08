package dev.jasagent.api.plugin;

import java.util.concurrent.CompletionStage;

public interface HallucinationGuard {
    CompletionStage<Void> check(String executionId, String modelOutput);
}
