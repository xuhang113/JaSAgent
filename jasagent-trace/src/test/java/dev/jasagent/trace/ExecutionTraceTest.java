package dev.jasagent.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTraceTest {
    @TempDir
    Path temp;

    @Test
    void traceFilesUnderExecutionDirectory() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        FileExecutionTrace trace = new FileExecutionTrace();
        join(trace.event(workspace, executionId, TraceEventKind.ACCEPTED, "accepted"));
        Path directory = workspace.resolve("jasagent").resolve("logs").resolve(executionId);
        assertEquals(directory, FileExecutionTrace.directory(workspace, executionId));
        assertTrue(Files.isRegularFile(directory.resolve("trace.log")));
        assertTrue(Files.isRegularFile(directory.resolve("events.jsonl")));
    }

    @Test
    void eventsCoverRequiredKinds() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        FileExecutionTrace trace = new FileExecutionTrace();
        for (TraceEventKind kind : TraceEventKind.values()) {
            join(trace.event(workspace, executionId, kind, "body-" + kind.name()));
        }
        Path directory = FileExecutionTrace.directory(workspace, executionId);
        String log = Files.readString(directory.resolve("trace.log"));
        String jsonl = Files.readString(directory.resolve("events.jsonl"));
        assertEquals(TraceEventKind.values().length, jsonl.lines().filter(line -> !line.isBlank()).count());
        for (TraceEventKind kind : TraceEventKind.values()) {
            assertTrue(log.contains(kind.name()), kind.name());
            assertTrue(jsonl.contains(kind.name()), kind.name());
        }
    }

    @Test
    void authorizationValueIsRemoved() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        FileExecutionTrace trace = new FileExecutionTrace();
        join(trace.event(workspace, executionId, TraceEventKind.MODEL_ROUTE, "Authorization: Bearer super-secret-token"));
        Path directory = FileExecutionTrace.directory(workspace, executionId);
        String all = Files.readString(directory.resolve("trace.log")) + Files.readString(directory.resolve("events.jsonl"));
        assertFalse(all.contains("super-secret-token"));
        assertFalse(all.contains("Bearer"));
    }

    @Test
    void stackGoesToTraceLog() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        FileExecutionTrace trace = new FileExecutionTrace();
        join(trace.stack(workspace, executionId, new IllegalStateException("stack-marker")));
        String log = Files.readString(FileExecutionTrace.directory(workspace, executionId).resolve("trace.log"));
        assertTrue(log.contains("stack-marker"));
        assertTrue(log.contains("at "));
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
