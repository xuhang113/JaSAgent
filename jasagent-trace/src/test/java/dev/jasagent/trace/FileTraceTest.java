package dev.jasagent.trace;

import dev.jasagent.api.trace.TraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTraceTest {
    @TempDir
    Path temp;

    @Test
    void traceFilesExist() throws Exception {
        FileTraceWriter writer = new FileTraceWriter();
        join(writer.event(temp, "exec-1", new TraceEvent("accepted", "accepted execution", Map.of())));
        Path dir = temp.resolve("jasagent").resolve("logs").resolve("exec-1");
        assertTrue(Files.isRegularFile(dir.resolve("trace.log")));
        assertTrue(Files.isRegularFile(dir.resolve("events.jsonl")));
        assertFalse(Files.readString(dir.resolve("trace.log")).isBlank());
        assertFalse(Files.readString(dir.resolve("events.jsonl")).isBlank());
    }

    @Test
    void acceptedAndTerminalEvents() {
        FileTraceWriter writer = new FileTraceWriter();
        join(writer.event(temp, "exec-2", new TraceEvent("accepted", "start", Map.of())));
        join(writer.event(temp, "exec-2", new TraceEvent("terminal", "SUCCEEDED", Map.of("status", "SUCCEEDED"))));
        String events = join(writer.readEvents(temp, "exec-2"));
        assertTrue(events.contains("accepted"));
        assertTrue(events.contains("terminal"));
    }

    @Test
    void traceRedactsAuthorization() {
        FileTraceWriter writer = new FileTraceWriter();
        join(writer.event(temp, "exec-3", new TraceEvent("accepted", "Authorization: Bearer trace-secret-placeholder", Map.of())));
        String log = join(writer.readLog(temp, "exec-3"));
        assertFalse(log.contains("trace-secret-placeholder"));
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
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
