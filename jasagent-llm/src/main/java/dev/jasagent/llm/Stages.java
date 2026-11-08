package dev.jasagent.llm;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class Stages {
    private Stages() {
    }

    static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AgentException(ErrorCode.LLM, AuthorizationRedactor.redact(cause.getMessage()), null, cause);
        }
    }
}
