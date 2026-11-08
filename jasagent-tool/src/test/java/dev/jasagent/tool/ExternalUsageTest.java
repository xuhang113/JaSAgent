package dev.jasagent.tool;

import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalUsageTest {
    @TempDir
    Path temp;

    @Test
    void clockLeavesSuccessUnchanged() {
        long before = CallStats.load().count("clock").success();
        join(clockCatalog().call(clockCall()));
        assertEquals(before, CallStats.load().count("clock").success());
    }

    @Test
    void clockLeavesFailureUnchanged() {
        long before = CallStats.load().count("clock").failure();
        join(clockCatalog().call(clockCall()));
        assertEquals(before, CallStats.load().count("clock").failure());
    }

    @Test
    void clockDoesNotAcquire() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        DefaultToolCatalog catalog = new DefaultToolCatalog(gate, new MemoryToolCallStore(), List.of(new ClockTool()));
        join(catalog.call(clockCall()));
        assertEquals(0, gate.acquisitions());
    }

    @Test
    void explicitClockRecordIncrementsSuccess() {
        CallStats stats = CallStats.load();
        long before = stats.count("clock").success();
        stats.recordSuccess("clock");
        assertEquals(before + 1, stats.count("clock").success());
    }

    @Test
    void weatherSuccessIncrementsOnce() {
        long before = CallStats.load().count("weather").success();
        Tool weather = new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("weather", "weather", false);
            }

            @Override
            public CompletionStage<String> call(ToolCall call) {
                return CompletableFuture.completedFuture("sunny");
            }
        };
        DefaultToolCatalog catalog = new DefaultToolCatalog(new ScriptedPassGate(PassDecision.ALLOW), new MemoryToolCallStore(), List.of(weather));
        join(catalog.call(new ToolCall("aa", "exec-weather", temp.resolve("user"), temp.resolve("workspace"), "weather", Map.of("city", "shanghai"))));
        assertEquals(before + 1, CallStats.load().count("weather").success());
    }

    private DefaultToolCatalog clockCatalog() {
        return new DefaultToolCatalog(new ScriptedPassGate(PassDecision.ALLOW), new MemoryToolCallStore(), List.of(new ClockTool()));
    }

    private ToolCall clockCall() {
        return new ToolCall("aa", "exec-clock", temp.resolve("user"), temp.resolve("workspace"), "clock", Map.of());
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

    private static final class ScriptedPassGate implements PassGate {
        private final List<PassRequest> requests = new CopyOnWriteArrayList<>();
        private final PassDecision decision;

        private ScriptedPassGate(PassDecision decision) {
            this.decision = decision;
        }

        private int acquisitions() {
            return requests.size();
        }

        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(new Pass(
                    UUID.randomUUID().toString(),
                    request.userId(),
                    request.executionId(),
                    request.toolName(),
                    request.pluginId(),
                    request.argumentHash(),
                    decision,
                    "policy"));
        }
    }
}
