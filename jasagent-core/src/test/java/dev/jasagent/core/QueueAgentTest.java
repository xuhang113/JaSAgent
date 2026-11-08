package dev.jasagent.core;

import dev.jasagent.api.workflow.WorkflowResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueAgentTest {
    @Test
    void defaultBatchSizeIsFour() {
        assertEquals(4, SchedulerConfig.defaults().batchSize());
    }

    @Test
    void scheduleTakesOnlyBatch() {
        AgentScheduler scheduler = new AgentScheduler(new SchedulerConfig(4, 0.6, 8), task -> done());
        for (int index = 0; index < 6; index++) {
            scheduler.enqueue(item("t" + index, "exec-" + index, "session-" + index, null, "word" + index));
        }
        scheduler.scheduleOnce();
        assertEquals(2, scheduler.mainQueueSize());
    }

    @Test
    void sameSessionSimilarityIsOne() {
        WorkItem left = item("a", "e1", "same", null, "alpha");
        WorkItem right = item("b", "e2", "same", null, "omega");
        assertEquals(1.0, AgentScheduler.similarity(left, right));
    }

    @Test
    void sameCorrelationSimilarityIsPointNine() {
        WorkItem left = item("a", "e1", "s1", "corr", "alpha");
        WorkItem right = item("b", "e2", "s2", "corr", "omega");
        assertEquals(0.9, AgentScheduler.similarity(left, right));
    }

    @Test
    void otherwiseTokenJaccard() {
        WorkItem left = item("a", "e1", "s1", null, "red cat");
        WorkItem right = item("b", "e2", "s2", null, "red dog");
        assertEquals(1.0 / 3.0, AgentScheduler.similarity(left, right), 0.000001);
    }

    @Test
    void mergeAtThreshold() {
        List<AgentTask> seen = new ArrayList<>();
        AgentScheduler scheduler = new AgentScheduler(SchedulerConfig.defaults(), task -> {
            seen.add(task);
            return done();
        });
        scheduler.enqueue(item("a", "e1", "same", null, "left-text"));
        scheduler.enqueue(item("b", "e2", "same", null, "right-text"));
        scheduler.scheduleOnce();
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).item().targetText().contains("left-text"));
        assertTrue(seen.get(0).item().targetText().contains("right-text"));
    }

    @Test
    void appendToUnfinishedSubagent() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AgentScheduler scheduler = new AgentScheduler(new SchedulerConfig(1, 0.6, 8), task -> blocked(release));
        scheduler.enqueue(item("a", "e1", "same", null, "one"));
        scheduler.scheduleOnce();
        scheduler.enqueue(item("b", "e2", "same", null, "two"));
        scheduler.scheduleOnce();
        assertEquals(1, scheduler.subagentCount());
        release.countDown();
    }

    @Test
    void capCreatesNewSubagent() {
        CountDownLatch release = new CountDownLatch(1);
        AgentScheduler scheduler = new AgentScheduler(new SchedulerConfig(1, 0.6, 8), task -> blocked(release));
        for (int index = 0; index < 8; index++) {
            scheduler.enqueue(item("t" + index, "e" + index, "same", null, "text-" + index));
            scheduler.scheduleOnce();
        }
        assertEquals(1, scheduler.subagentCount());
        scheduler.enqueue(item("t8", "e8", "same", null, "text-8"));
        scheduler.scheduleOnce();
        assertEquals(2, scheduler.subagentCount());
        release.countDown();
    }

    @Test
    void mainAgentDoesNotExecuteWorkflow() {
        List<String> roles = new ArrayList<>();
        AgentScheduler scheduler = new AgentScheduler(SchedulerConfig.defaults(), task -> {
            roles.add(task.role());
            return done();
        });
        scheduler.enqueue(item("a", "e1", "s1", null, "alpha"));
        scheduler.scheduleOnce();
        assertFalse(roles.contains("MAIN"));
        assertEquals(List.of("SUB"), roles);
    }

    @Test
    void subagentQueueIsSerial() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AgentScheduler scheduler = new AgentScheduler(new SchedulerConfig(1, 0.6, 8), task -> {
            calls.incrementAndGet();
            started.countDown();
            return blocked(release);
        });
        scheduler.enqueue(item("a", "e1", "same", null, "one"));
        scheduler.scheduleOnce();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        scheduler.enqueue(item("b", "e2", "same", null, "two"));
        scheduler.scheduleOnce();
        Thread.sleep(150);
        assertEquals(1, calls.get());
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (calls.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, calls.get());
    }

    @Test
    void subagentsRunInParallel() throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AgentScheduler scheduler = new AgentScheduler(new SchedulerConfig(2, 0.6, 8), task -> {
            both.countDown();
            return blocked(release);
        });
        scheduler.enqueue(item("a", "e1", "s1", null, "alpha"));
        scheduler.enqueue(item("b", "e2", "s2", null, "omega"));
        scheduler.scheduleOnce();
        assertTrue(both.await(3, TimeUnit.SECONDS));
        release.countDown();
    }

    @Test
    void missingSessionBecomesExecutionId() {
        List<AgentTask> seen = new ArrayList<>();
        AgentScheduler scheduler = new AgentScheduler(SchedulerConfig.defaults(), task -> {
            seen.add(task);
            return done();
        });
        scheduler.enqueue(item("a", "exec-9", null, null, "alpha"));
        scheduler.scheduleOnce();
        assertEquals("exec-9", seen.get(0).item().sessionId());
    }

    @Test
    void submitReturnsBeforeCompletion() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MemoryExecutions memory = new MemoryExecutions();
        AgentScheduler scheduler = new AgentScheduler(SchedulerConfig.defaults(), task -> blocked(release));
        SchedulingExecutionService service = new SchedulingExecutionService(scheduler, memory);
        String id = service.submit(new SubmitCommand("user-a", null, "/ws", "hello")).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertNotEquals("SUCCEEDED", memory.status(id));
        release.countDown();
    }

    private static final class MemoryExecutions implements ExecutionLog {
        private final Map<String, String> statuses = new ConcurrentHashMap<>();

        @Override
        public void accepted(String executionId, String userId, String sessionId, String workspace) {
            statuses.put(executionId, "ACCEPTED");
        }

        @Override
        public String status(String executionId) {
            return statuses.get(executionId);
        }
    }

    private static WorkItem item(String taskId, String executionId, String sessionId, String correlationKey, String text) {
        return new WorkItem(taskId, executionId, sessionId, correlationKey, text, "{}", text);
    }

    private static CompletableFuture<WorkflowResult> done() {
        return CompletableFuture.completedFuture(new WorkflowResult("SUCCEEDED", "ok", null, List.of()));
    }

    private static CompletableFuture<WorkflowResult> blocked(CountDownLatch release) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return new WorkflowResult("SUCCEEDED", "ok", null, List.of());
        });
    }
}
