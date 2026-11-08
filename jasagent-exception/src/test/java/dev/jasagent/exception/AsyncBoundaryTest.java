package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncBoundaryTest {
    @Test
    void nullBoundaryFails() {
        AsyncGuard guard = new AsyncGuard();
        CompletionStage<String> stage = guard.boundary(null, "exec-null");
        AgentException error = assertThrows(AgentException.class, () -> join(stage));
        assertEquals(ErrorCode.INTERNAL, error.code());
        assertTrue(stage.toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void consumerMarksFailedAndContinues() {
        TaskConsumer consumer = new TaskConsumer();
        List<String> failed = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        consumer.consume("task-a", () -> {
            throw new IllegalStateException("boom");
        }, (taskId, status, error) -> {
            assertEquals("FAILED", status);
            failed.add(taskId);
        });
        consumer.consume("task-b", () -> ran.add("task-b"), (taskId, status, error) -> failed.add(taskId));
        assertEquals(List.of("task-a"), failed);
        assertEquals(List.of("task-b"), ran);
    }

    @Test
    void errorCodesCoverSpec() {
        for (String name : List.of(
                "LLM", "TOOL", "PLUGIN", "MCP", "RAG", "WORKFLOW", "PERSIST",
                "AUTH", "INTERRUPTED", "CANCELLED", "INTERNAL", "WORKFLOW_MAX_STEPS")) {
            ErrorCode.valueOf(name);
        }
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
