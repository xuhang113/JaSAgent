package dev.jasagent.api.plugin;

import java.util.concurrent.CompletionStage;

public interface HallucinationGuard {
    CompletionStage<Boolean> grounded(String answer, String evidence, String executionId);
}
