package dev.jasagent.tool;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassLog;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCallContext;
import dev.jasagent.api.tool.ToolSpec;
import dev.jasagent.api.workflow.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolPassTest {
    @Test
    void duplicateToolRejected() {
        AgentException error = assertThrows(AgentException.class,
                () -> new ToolCatalog(List.of(new ClockTool(Clock.systemUTC()), new ClockTool(Clock.systemUTC()))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void clockReturnsUtcWithoutPass() {
        Instant instant = Instant.parse("2026-11-08T01:02:03Z");
        CountingGate gate = new CountingGate(new Pass(PassDecision.DENY, "no"));
        List<Pass> logged = new ArrayList<>();
        ToolResult result = join(new GatedToolRunner().run(
                new ClockTool(Clock.fixed(instant, ZoneOffset.UTC)),
                new ToolCallContext("user-a", "exec-1", "{}"),
                gate,
                (executionId, kind, name, pass) -> logged.add(pass)));
        assertEquals(instant.toString(), result.text());
        assertEquals(0, gate.calls.get());
        assertEquals(0, logged.size());
    }

    @Test
    void externalToolAcquiresPass() {
        CountingGate gate = new CountingGate(new Pass(PassDecision.ALLOW, "ok"));
        CounterTool tool = new CounterTool(false);
        join(new GatedToolRunner().run(tool, new ToolCallContext("user-a", "exec-1", "{\"a\":1}"), gate, (a, b, c, d) -> {
        }));
        assertEquals(1, gate.calls.get());
        assertEquals("external.echo", gate.last.toolName());
    }

    @Test
    void denySkipsToolBody() {
        CountingGate gate = new CountingGate(new Pass(PassDecision.DENY, "no"));
        CounterTool tool = new CounterTool(false);
        List<Pass> logged = new ArrayList<>();
        join(new GatedToolRunner().run(tool, new ToolCallContext("user-a", "exec-1", "{}"), gate,
                (executionId, kind, name, pass) -> logged.add(pass)));
        assertEquals(0, tool.count.get());
        assertEquals(PassDecision.DENY, logged.get(0).decision());
    }

    @Test
    void pendingDoesNotRunTool() {
        CounterTool tool = new CounterTool(false);
        ToolResult result = join(new GatedToolRunner().run(tool, new ToolCallContext("user-a", "exec-1", "{}"),
                new CountingGate(new Pass(PassDecision.PENDING, "approval-required")), (a, b, c, d) -> {
                }));
        assertEquals(0, tool.count.get());
        assertFalse(result.success());
        assertEquals("approval-required", result.text());
    }

    @Test
    void skippedRunsTool() {
        CounterTool tool = new CounterTool(false);
        join(new GatedToolRunner().run(tool, new ToolCallContext("user-a", "exec-1", "{}"),
                new CountingGate(new Pass(PassDecision.SKIPPED, "agentpass-not-enabled")), (a, b, c, d) -> {
                }));
        assertEquals(1, tool.count.get());
    }

    @Test
    void allowRunsTool() {
        CounterTool tool = new CounterTool(false);
        join(new GatedToolRunner().run(tool, new ToolCallContext("user-a", "exec-1", "{}"),
                new CountingGate(new Pass(PassDecision.ALLOW, "ok")), (a, b, c, d) -> {
                }));
        assertEquals(1, tool.count.get());
    }

    @Test
    void passDecisionIsRecorded() {
        Pass pass = new Pass(PassDecision.ALLOW, "ok");
        List<Pass> logged = new ArrayList<>();
        join(new GatedToolRunner().run(new CounterTool(false), new ToolCallContext("user-a", "exec-1", "{}"),
                new CountingGate(pass), (executionId, kind, name, recorded) -> logged.add(recorded)));
        assertEquals(1, logged.size());
        assertEquals(pass.decision(), logged.get(0).decision());
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

    private static final class CountingGate implements PassGate {
        private final Pass pass;
        private final AtomicInteger calls = new AtomicInteger();
        private PassRequest last;

        private CountingGate(Pass pass) {
            this.pass = pass;
        }

        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            calls.incrementAndGet();
            last = request;
            return CompletableFuture.completedFuture(pass);
        }
    }

    private static final class CounterTool implements Tool {
        private final AtomicInteger count = new AtomicInteger();
        private final boolean internal;

        private CounterTool(boolean internal) {
            this.internal = internal;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("external.echo", "echo", internal);
        }

        @Override
        public CompletionStage<dev.jasagent.api.workflow.ToolResult> call(ToolCallContext context) {
            count.incrementAndGet();
            return CompletableFuture.completedFuture(new ToolResult("echo", true));
        }
    }
}
