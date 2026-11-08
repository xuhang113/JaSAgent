package dev.jasagent.persist;

import dev.jasagent.api.persist.ExecutionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistStoreTest {
    @TempDir
    Path temp;

    @Test
    void executionRoundTrip() {
        Path workspace = temp.resolve("ws");
        JdbcExecutionStore store = new JdbcExecutionStore(JdbcSettings.from(Map.of(), Map.of(), workspace));
        try {
            ExecutionRecord written = sample("exec-round");
            join(store.insertExecution(written));
            ExecutionRecord found = join(store.findExecution(written.executionId()));
            assertEquals(written.executionId(), found.executionId());
            assertEquals(written.userId(), found.userId());
            assertEquals(written.sessionId(), found.sessionId());
            assertEquals(written.workspacePath(), found.workspacePath());
            assertEquals(written.status(), found.status());
            assertEquals(written.otelTraceId(), found.otelTraceId());
            assertEquals(written.otelRootSpanId(), found.otelRootSpanId());
        } finally {
            store.close();
        }
    }

    @Test
    void defaultJdbcPathIsWorkspaceH2() {
        Path workspace = temp.resolve("ws");
        JdbcSettings settings = JdbcSettings.from(Map.of(), Map.of(), workspace);
        String expected = workspace.resolve("jasagent").resolve("data").resolve("jasagent").toAbsolutePath().normalize().toString();
        assertTrue(settings.url().startsWith("jdbc:h2:file:"));
        assertTrue(settings.url().contains(expected));
        assertEquals("", settings.password());
    }

    @Test
    void jdbcUrlComesFromEnvNotProperties() {
        JdbcSettings settings = JdbcSettings.from(
                Map.of("JASAGENT_JDBC_URL", "jdbc:h2:mem:from-env"),
                Map.of("JASAGENT_JDBC_PASSWORD", "ignored-value"),
                temp.resolve("ws"));
        assertEquals("jdbc:h2:mem:from-env", settings.url());
        assertEquals("", settings.password());
    }

    @Test
    void reopenKeepsExecution() {
        Path workspace = temp.resolve("ws");
        JdbcSettings settings = JdbcSettings.from(Map.of(), Map.of(), workspace);
        ExecutionRecord written = sample("exec-reopen");
        JdbcExecutionStore first = new JdbcExecutionStore(settings);
        join(first.insertExecution(written));
        first.close();
        JdbcExecutionStore second = new JdbcExecutionStore(settings);
        try {
            ExecutionRecord found = join(second.findExecution(written.executionId()));
            assertEquals(written.executionId(), found.executionId());
            assertEquals(written.otelTraceId(), found.otelTraceId());
        } finally {
            second.close();
        }
    }

    private static ExecutionRecord sample(String executionId) {
        return new ExecutionRecord(
                executionId,
                "user-a",
                "session-a",
                "/workspace/demo",
                "ACCEPTED",
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                null,
                null);
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(20, TimeUnit.SECONDS);
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
