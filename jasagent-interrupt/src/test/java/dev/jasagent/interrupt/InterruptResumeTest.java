package dev.jasagent.interrupt;

import dev.jasagent.api.persist.RunSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptResumeTest {
    @Test
    void interruptSetsSuspended() {
        MemoryRuns runs = seed();
        InterruptService service = new InterruptService(runs);
        CompletableFuture<String> stage = new CompletableFuture<>();
        service.register("exec-1", stage);
        RunSnapshot suspended = service.interrupt("exec-1");
        assertEquals("SUSPENDED", suspended.status());
        assertTrue(stage.isCancelled());
    }

    @Test
    void interruptKeepsCheckpoint() {
        MemoryRuns runs = seed();
        InterruptService service = new InterruptService(runs);
        service.register("exec-1", new CompletableFuture<>());
        service.interrupt("exec-1");
        assertEquals("find", runs.find("exec-1").checkpointNode());
    }

    @Test
    void resumeUsesOriginalId() {
        MemoryRuns runs = seed();
        InterruptService service = new InterruptService(runs);
        service.register("exec-1", new CompletableFuture<>());
        service.interrupt("exec-1");
        RunSnapshot resumed = service.resume("exec-1", snapshot -> snapshot.withStatus("SUCCEEDED").withOutput("done"));
        assertEquals("exec-1", resumed.executionId());
        assertEquals("done", resumed.output());
    }

    @Test
    void resumeOfActiveReturnsSnapshot() {
        MemoryRuns runs = seed();
        runs.save(runs.find("exec-1").withStatus("SUCCEEDED").withOutput("already"));
        AtomicInteger calls = new AtomicInteger();
        InterruptService service = new InterruptService(runs);
        RunSnapshot again = service.resume("exec-1", snapshot -> {
            calls.incrementAndGet();
            return snapshot.withOutput("nope");
        });
        assertEquals("SUCCEEDED", again.status());
        assertEquals("already", again.output());
        assertEquals(0, calls.get());
    }

    @Test
    void resumeKeepsTraceId() {
        MemoryRuns runs = seed();
        InterruptService service = new InterruptService(runs);
        service.register("exec-1", new CompletableFuture<>());
        service.interrupt("exec-1");
        RunSnapshot resumed = service.resume("exec-1", snapshot -> snapshot.withStatus("SUCCEEDED").withOutput("done"));
        assertEquals("0123456789abcdef0123456789abcdef", resumed.traceId());
    }

    private static MemoryRuns seed() {
        MemoryRuns runs = new MemoryRuns();
        runs.save(new RunSnapshot("exec-1", "RUNNING", "find", "0123456789abcdef0123456789abcdef", ""));
        return runs;
    }
}
