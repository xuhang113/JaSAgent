package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncExceptionTest {
    @Test
    void errorCodesPresent() {
        Set<ErrorCode> required = EnumSet.of(
                ErrorCode.LLM,
                ErrorCode.TOOL,
                ErrorCode.PLUGIN,
                ErrorCode.MCP,
                ErrorCode.RAG,
                ErrorCode.WORKFLOW,
                ErrorCode.WORKFLOW_MAX_STEPS,
                ErrorCode.PERSIST,
                ErrorCode.AUTH,
                ErrorCode.INTERRUPTED,
                ErrorCode.CANCELLED,
                ErrorCode.INTERNAL);
        assertTrue(EnumSet.allOf(ErrorCode.class).containsAll(required));
    }

    @Test
    void swallowedNullIsRejected() {
        String executionId = UUID.randomUUID().toString();
        Callable<String> swallowed = () -> {
            try {
                throw new IllegalStateException("hidden");
            } catch (RuntimeException ex) {
                return null;
            }
        };
        AgentException error = assertThrows(AgentException.class, () -> join(AsyncBoundary.guard(executionId, swallowed)));
        assertEquals(ErrorCode.INTERNAL, error.code());
        assertEquals(executionId, error.executionId());
    }

    @Test
    void thrownExceptionIsNotReturnedAsNull() {
        String executionId = UUID.randomUUID().toString();
        AgentException error = assertThrows(AgentException.class, () -> join(AsyncBoundary.guard(executionId, () -> {
            throw new IllegalStateException("boom");
        })));
        assertEquals(executionId, error.executionId());
        assertNotNull(error.getCause());
    }

    @Test
    void consumerMarksTaskFailedAndContinues() {
        String executionId = UUID.randomUUID().toString();
        List<String> failed = new ArrayList<>();
        TaskFailureSink sink = (id, taskId, error) -> {
            assertEquals(executionId, id);
            assertEquals(ErrorCode.TOOL, error.code());
            assertEquals(executionId, error.executionId());
            failed.add(taskId);
        };
        TaskConsumer consumer = new TaskConsumer();
        ConsumeOutcome first = join(consumer.consume(executionId, "task-1", () -> {
            throw new AgentException(ErrorCode.TOOL, "nope", executionId, null);
        }, sink));
        assertEquals(ConsumeOutcome.FAILED, first);
        assertEquals(List.of("task-1"), failed);
        ConsumeOutcome second = join(consumer.consume(executionId, "task-2", () -> "ok", sink));
        assertEquals(ConsumeOutcome.COMPLETED, second);
        assertEquals(List.of("task-1"), failed);
    }

    @Test
    void httpBodyHasExecutionIdAndCodeWithoutStack() {
        String executionId = UUID.randomUUID().toString();
        List<String> stacks = new ArrayList<>();
        AgentException error = new AgentException(ErrorCode.TOOL, "stack-marker", executionId, new IllegalStateException("stack-marker"));
        HttpErrorBody body = HttpErrors.body(error, stacks::add);
        assertEquals(executionId, body.executionId());
        assertEquals("TOOL", body.code());
        assertTrue(!body.toString().contains("stack-marker"));
        assertTrue(!body.toString().contains("at "));
        assertEquals(1, stacks.size());
        assertTrue(stacks.get(0).contains("stack-marker"));
        assertTrue(stacks.get(0).contains("at "));
    }

    @Test
    void guardRunsOnVirtualThread() {
        String executionId = UUID.randomUUID().toString();
        AtomicBoolean virtual = new AtomicBoolean(false);
        join(AsyncBoundary.guard(executionId, () -> {
            virtual.set(Thread.currentThread().isVirtual());
            return "ok";
        }));
        assertTrue(virtual.get());
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
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
