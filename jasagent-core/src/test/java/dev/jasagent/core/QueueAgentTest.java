package dev.jasagent.core;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.schedule.ScheduleBatch;
import dev.jasagent.api.schedule.SubAgentRunner;
import dev.jasagent.api.task.AgentRole;
import dev.jasagent.api.task.TaskSnapshot;
import dev.jasagent.api.task.WorkItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(20)
class QueueAgentTest {
    private static final SubAgentRunner DONE = item -> CompletableFuture.completedFuture(null);

    @Test
    void defaultsWhenUnset() {
        assertDefaults(new QueueAgentScheduler(null, DONE).settings());
        assertDefaults(new QueueAgentScheduler(Map.of(), DONE).settings());
    }

    @Test
    void configurationReplacesDefaults() {
        SchedulerSettings settings = new QueueAgentScheduler(Map.of(
                SchedulerSettings.BATCH_SIZE, "2",
                SchedulerSettings.MERGE_THRESHOLD, "0.9",
                SchedulerSettings.QUEUE_CAP, "3"), DONE).settings();
        assertEquals(2, settings.batchSize());
        assertEquals(0.9, settings.mergeThreshold(), 1e-9);
        assertEquals(3, settings.queueCap());
    }

    @Test
    void configuredBatchSizeLimitsTake() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(SchedulerSettings.BATCH_SIZE, "2"), DONE);
        join(scheduler.enqueue(separated("t1")));
        join(scheduler.enqueue(separated("t2")));
        join(scheduler.enqueue(separated("t3")));
        ScheduleBatch batch = join(scheduler.scheduleOnce());
        assertEquals(2, batch.taken());
        assertEquals(2, batch.assigned().size());
        assertEquals(List.of("t3"), ids(scheduler.queue(QueueAgentScheduler.MAIN_ID)));
        join(scheduler.onIdle());
    }

    @Test
    void invalidSettingsFailConstruction() {
        for (String batch : List.of("0", "-3", "2.5", "abc", " ")) {
            AgentException error = assertThrows(AgentException.class, () -> new QueueAgentScheduler(
                    Map.of(SchedulerSettings.BATCH_SIZE, batch), DONE));
            assertEquals(ErrorCode.INTERNAL, error.code());
        }
        for (String threshold : List.of("1.1", "-0.01", "abc", " ", "NaN")) {
            AgentException error = assertThrows(AgentException.class, () -> new QueueAgentScheduler(
                    Map.of(SchedulerSettings.MERGE_THRESHOLD, threshold), DONE));
            assertEquals(ErrorCode.INTERNAL, error.code());
        }
        for (String cap : List.of("0", "abc")) {
            AgentException error = assertThrows(AgentException.class, () -> new QueueAgentScheduler(
                    Map.of(SchedulerSettings.QUEUE_CAP, cap), DONE));
            assertEquals(ErrorCode.INTERNAL, error.code());
        }
    }

    @Test
    void sameSessionScoresOne() {
        WorkItem left = item("t1", "e1", "same", "k1", "alpha beta");
        WorkItem right = item("t2", "e2", "same", "k2", "gamma delta");
        assertEquals(1.0, WorkCorrelation.score(left, right), 0.0);
    }

    @Test
    void sameCorrelationKeyScoresPointNine() {
        WorkItem left = item("t1", "e1", "s1", "shared", "same text");
        WorkItem right = item("t2", "e2", "s2", "shared", "same text");
        assertEquals(0.9, WorkCorrelation.score(left, right), 0.0);
    }

    @Test
    void otherwiseTokenJaccard() {
        WorkItem apple = item("t1", "e1", "s1", "k1", "red apple");
        WorkItem banana = item("t2", "e2", "s2", "k2", "red banana");
        WorkItem alpha = item("t3", "e3", "s3", "k3", "alpha beta");
        WorkItem gamma = item("t4", "e4", "s4", "k4", "gamma delta");
        assertEquals(1.0 / 3.0, WorkCorrelation.score(apple, banana), 1e-9);
        assertEquals(0.0, WorkCorrelation.score(alpha, gamma), 0.0);
    }

    @Test
    void blankSessionDoesNotMatch() {
        WorkItem left = item("t1", "e1", " ", "k1", "alpha");
        WorkItem right = item("t2", "e2", "", "k2", "beta");
        assertEquals(0.0, WorkCorrelation.score(left, right), 0.0);
    }

    @Test
    void blankCorrelationKeyDoesNotMatch() {
        WorkItem left = item("t1", "e1", "s1", " ", "alpha");
        WorkItem right = item("t2", "e2", "s2", "", "beta");
        assertEquals(0.0, WorkCorrelation.score(left, right), 0.0);
    }

    @Test
    void caseDifferenceDoesNotMatch() {
        WorkItem left = item("t1", "e1", "Session", "Key", "alpha");
        WorkItem right = item("t2", "e2", "session", "key", "beta");
        assertEquals(0.0, WorkCorrelation.score(left, right), 0.0);
    }

    @Test
    void tokensIgnoreCasePunctuationAndDuplicates() {
        assertEquals(1.0, WorkCorrelation.score(
                item("t1", "e1", "s1", "k1", "Red, Apple!"),
                item("t2", "e2", "s2", "k2", "red apple")), 1e-9);
        assertEquals(1.0, WorkCorrelation.score(
                item("t3", "e3", "s3", "k3", "red red blue"),
                item("t4", "e4", "s4", "k4", "red blue")), 1e-9);
        assertEquals(0.5, WorkCorrelation.score(
                item("t5", "e5", "s5", "k5", "你好 世界"),
                item("t6", "e6", "s6", "k6", "你好")), 1e-9);
        assertEquals(0.0, WorkCorrelation.score(
                item("t7", "e7", "s7", "k7", ""),
                item("t8", "e8", "s8", "k8", null)), 0.0);
    }

    @Test
    void blankSessionBecomesExecutionId() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(item("t1", "exec-blank", "  ", "k1", "alpha")));
        TaskSnapshot stored = scheduler.queue(QueueAgentScheduler.MAIN_ID).get(0);
        assertEquals("exec-blank", stored.item().sessionId());
    }

    @Test
    void enqueueStaysOnMainUntilScheduled() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        join(scheduler.enqueue(item("t1", "e1", "s1", "k1", "alpha")));
        List<TaskSnapshot> queue = scheduler.queue(QueueAgentScheduler.MAIN_ID);
        assertEquals(List.of("t1"), ids(queue));
        assertEquals(QueueAgentScheduler.MAIN_ID, queue.get(0).agentId());
        assertEquals(AgentRole.MAIN, queue.get(0).role());
        assertEquals("QUEUED", queue.get(0).status());
        assertEquals(0, runner.entered.get());
    }

    @Test
    void emptyScheduleTakesNothing() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        ScheduleBatch batch = join(scheduler.scheduleOnce());
        assertEquals(0, batch.taken());
        assertTrue(batch.assigned().isEmpty());
        assertEquals(0, runner.entered.get());
    }

    @Test
    void scheduleOnceTakesAtMostBatchSize() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        for (int index = 1; index <= 5; index++) {
            join(scheduler.enqueue(separated("t" + index)));
        }
        ScheduleBatch first = join(scheduler.scheduleOnce());
        assertEquals(4, first.taken());
        assertEquals(List.of("t5"), ids(scheduler.queue(QueueAgentScheduler.MAIN_ID)));
        ScheduleBatch second = join(scheduler.scheduleOnce());
        assertEquals(1, second.taken());
        assertEquals("t5", second.assigned().get(0).taskId());
        assertTrue(scheduler.queue(QueueAgentScheduler.MAIN_ID).isEmpty());
        join(scheduler.onIdle());
    }

    @Test
    void sameSessionMergesToOneSubAgent() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(item("t1", "e1", "same", "k1", "alpha beta")));
        join(scheduler.enqueue(item("t2", "e2", "same", "k2", "gamma delta")));
        ScheduleBatch batch = join(scheduler.scheduleOnce());
        assertEquals("sub-1", batch.assigned().get(0).agentId());
        assertEquals("sub-1", batch.assigned().get(1).agentId());
        assertEquals(List.of("sub-1"), subIds(scheduler));
        join(scheduler.onIdle());
    }

    @Test
    void belowThresholdStaysSeparate() {
        WorkItem left = item("t1", "e1", "s1", "k1", "a b");
        WorkItem right = item("t2", "e2", "s2", "k2", "a c");
        assertTrue(WorkCorrelation.score(left, right) < 0.6);
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(left));
        join(scheduler.enqueue(right));
        ScheduleBatch batch = join(scheduler.scheduleOnce());
        assertFalse(batch.assigned().get(0).agentId().equals(batch.assigned().get(1).agentId()));
        join(scheduler.onIdle());
    }

    @Test
    void equalThresholdMerges() {
        WorkItem left = item("t1", "e1", "s1", "k1", "a b c d");
        WorkItem right = item("t2", "e2", "s2", "k2", "a b c e");
        assertEquals(0.6, WorkCorrelation.score(left, right), 1e-9);
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(left));
        join(scheduler.enqueue(right));
        ScheduleBatch batch = join(scheduler.scheduleOnce());
        assertEquals(batch.assigned().get(0).agentId(), batch.assigned().get(1).agentId());
        join(scheduler.onIdle());
    }

    @Test
    void singleLinkageMergesChain() {
        WorkItem first = item("a", "ea", "sa", "ka", "a b c d");
        WorkItem second = item("b", "eb", "sb", "kb", "a b c e");
        WorkItem third = item("c", "ec", "sc", "kc", "a b e f");
        WorkItem fourth = item("d", "ed", "sd", "kd", "xxxx yyyy");
        assertTrue(WorkCorrelation.score(first, third) < 0.6);
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(first));
        join(scheduler.enqueue(second));
        join(scheduler.enqueue(third));
        join(scheduler.enqueue(fourth));
        join(scheduler.scheduleOnce());
        String grouped = agentOf(scheduler, "a");
        assertEquals(grouped, agentOf(scheduler, "b"));
        assertEquals(grouped, agentOf(scheduler, "c"));
        assertEquals(List.of("a", "b", "c"), ids(scheduler.queue(grouped)));
        assertFalse(grouped.equals(agentOf(scheduler, "d")));
        join(scheduler.onIdle());
    }

    @Test
    void mergedItemsKeepEnqueueOrder() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(item("p", "ep", "same", "kp", "p1")));
        join(scheduler.enqueue(item("q", "eq", "same", "kq", "q1")));
        join(scheduler.enqueue(item("r", "er", "same", "kr", "r1")));
        join(scheduler.scheduleOnce());
        assertEquals(List.of("p", "q", "r"), ids(scheduler.queue("sub-1")));
        join(scheduler.onIdle());
    }

    @Test
    void mergedGroupAppendsToUnfinishedAgent() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        join(scheduler.enqueue(item("t0", "e0", "same", "k0", "zero")));
        join(scheduler.scheduleOnce());
        awaitEntered(runner, 1);
        join(scheduler.enqueue(item("t1", "e1", "same", "k1", "one")));
        join(scheduler.enqueue(item("t2", "e2", "same", "k2", "two")));
        join(scheduler.scheduleOnce());
        assertEquals(List.of("sub-1"), subIds(scheduler));
        assertEquals(List.of("t0", "t1", "t2"), ids(scheduler.queue("sub-1")));
        runner.release("t0");
        runner.release("t1");
        runner.release("t2");
        join(scheduler.onIdle());
    }

    @Test
    void reusesHighestRelatedUnfinished() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        join(scheduler.enqueue(item("high", "eh", "sa", "ka", "aa one")));
        join(scheduler.enqueue(item("low", "el", "sb", "shared", "bb one")));
        join(scheduler.scheduleOnce());
        awaitEntered(runner, 2);
        join(scheduler.enqueue(item("next", "en", "sa", "shared", "nn one")));
        join(scheduler.scheduleOnce());
        assertEquals(agentOf(scheduler, "high"), agentOf(scheduler, "next"));
        assertFalse(agentOf(scheduler, "low").equals(agentOf(scheduler, "next")));
        runner.release("high");
        runner.release("low");
        runner.release("next");
        join(scheduler.onIdle());
    }

    @Test
    void fullBestAgentOpensNewInsteadOfWeaker() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(SchedulerSettings.QUEUE_CAP, "2"), runner);
        join(scheduler.enqueue(item("h1", "eh1", "high", "h-key", "hh one")));
        join(scheduler.enqueue(item("l1", "el1", "low", "shared", "ll one")));
        join(scheduler.scheduleOnce());
        awaitEntered(runner, 2);
        join(scheduler.enqueue(item("h2", "eh2", "high", "h-key-2", "hh two")));
        join(scheduler.scheduleOnce());
        join(scheduler.enqueue(item("next", "en", "high", "shared", "nn one")));
        join(scheduler.scheduleOnce());
        String high = agentOf(scheduler, "h1");
        String low = agentOf(scheduler, "l1");
        String created = agentOf(scheduler, "next");
        assertEquals(high, agentOf(scheduler, "h2"));
        assertFalse(created.equals(high));
        assertFalse(created.equals(low));
        assertEquals(3, subIds(scheduler).size());
        runner.release("h1");
        runner.release("h2");
        runner.release("l1");
        runner.release("next");
        join(scheduler.onIdle());
    }

    @Test
    void overflowWithinGroupOpensNewAgent() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(
                SchedulerSettings.QUEUE_CAP, "2",
                SchedulerSettings.BATCH_SIZE, "4"), DONE);
        for (int index = 1; index <= 4; index++) {
            join(scheduler.enqueue(item("t" + index, "e" + index, "same", "k" + index, "word" + index)));
        }
        join(scheduler.scheduleOnce());
        assertEquals(List.of("t1", "t2"), ids(scheduler.queue("sub-1")));
        assertEquals(List.of("t3", "t4"), ids(scheduler.queue("sub-2")));
        join(scheduler.onIdle());
    }

    @Test
    void unrelatedUnfinishedAgentIsNotReused() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        join(scheduler.enqueue(item("t1", "e1", "s1", "k1", "alpha one")));
        join(scheduler.scheduleOnce());
        awaitEntered(runner, 1);
        join(scheduler.enqueue(item("t2", "e2", "s2", "k2", "beta two")));
        join(scheduler.scheduleOnce());
        assertFalse(agentOf(scheduler, "t1").equals(agentOf(scheduler, "t2")));
        assertEquals(2, subIds(scheduler).size());
        runner.release("t1");
        runner.release("t2");
        join(scheduler.onIdle());
    }

    @Test
    void finishedAgentIsNotReused() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(item("t1", "e1", "same", "k1", "alpha")));
        join(scheduler.scheduleOnce());
        join(scheduler.onIdle());
        assertEquals("SUCCEEDED", scheduler.queue(agentOf(scheduler, "t1")).get(0).status());
        join(scheduler.enqueue(item("t2", "e2", "same", "k2", "beta")));
        join(scheduler.scheduleOnce());
        join(scheduler.onIdle());
        assertFalse(agentOf(scheduler, "t1").equals(agentOf(scheduler, "t2")));
    }

    @Test
    void eachAgentHasSingleQueue() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(item("t1", "e1", "same", "k1", "alpha")));
        join(scheduler.enqueue(item("t2", "e2", "same", "k2", "beta")));
        join(scheduler.enqueue(separated("t3")));
        join(scheduler.scheduleOnce());
        assertEquals(1, scheduler.agentIds().stream().filter(QueueAgentScheduler.MAIN_ID::equals).count());
        assertEquals(scheduler.agentIds().size(), new HashSet<>(scheduler.agentIds()).size());
        Set<String> seen = new HashSet<>();
        for (String agentId : scheduler.agentIds()) {
            for (TaskSnapshot snapshot : scheduler.queue(agentId)) {
                assertTrue(seen.add(snapshot.taskId()));
            }
        }
        assertEquals(Set.of("t1", "t2", "t3"), seen);
        join(scheduler.onIdle());
    }

    @Test
    void subAgentQueueIsSerial() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        join(scheduler.enqueue(item("t1", "e1", "same", "k1", "alpha")));
        join(scheduler.enqueue(item("t2", "e2", "same", "k2", "beta")));
        join(scheduler.scheduleOnce());
        awaitEntered(runner, 1);
        assertEquals(1, runner.entered.get());
        assertEquals(List.of("t1"), runner.order);
        List<TaskSnapshot> queue = scheduler.queue("sub-1");
        assertEquals("RUNNING", queue.get(0).status());
        assertEquals("QUEUED", queue.get(1).status());
        runner.release("t1");
        awaitEntered(runner, 2);
        assertEquals(List.of("t1", "t2"), runner.order);
        assertEquals(1, runner.maxInflight.get());
        runner.release("t2");
        join(scheduler.onIdle());
    }

    @Test
    void subAgentsRunInParallel() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        join(scheduler.enqueue(separated("t1")));
        join(scheduler.enqueue(separated("t2")));
        join(scheduler.scheduleOnce());
        awaitEntered(runner, 2);
        assertEquals(2, runner.maxInflight.get());
        assertEquals(2, subIds(scheduler).size());
        runner.release("t1");
        runner.release("t2");
        join(scheduler.onIdle());
    }

    @Test
    void mainAgentOnlySchedules() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        join(scheduler.enqueue(separated("t1")));
        join(scheduler.enqueue(item("t2", "e2", "s2", "k2", "beta two")));
        ScheduleBatch batch = join(scheduler.scheduleOnce());
        assertEquals(2, batch.assigned().size());
        for (TaskSnapshot snapshot : batch.assigned()) {
            assertEquals(AgentRole.SUB, snapshot.role());
            assertFalse(QueueAgentScheduler.MAIN_ID.equals(snapshot.agentId()));
        }
        awaitEntered(runner, 2);
        for (String taskId : runner.order) {
            assertTrue(scheduler.queue(QueueAgentScheduler.MAIN_ID).stream().noneMatch(item -> item.taskId().equals(taskId)));
        }
        runner.release("t1");
        runner.release("t2");
        join(scheduler.onIdle());
    }

    @Test
    void scheduleReturnsBeforeRunFinishes() throws Exception {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        CompletionStage<Void> enqueued = scheduler.enqueue(item("t1", "e1", "s1", "k1", "alpha beta"));
        assertEquals(0, runner.entered.get());
        join(enqueued);
        assertEquals(0, runner.entered.get());
        ScheduleBatch batch = scheduler.scheduleOnce().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(1, batch.taken());
        assertEquals("QUEUED", batch.assigned().get(0).status());
        assertTrue(runner.gates.isEmpty() || runner.gates.values().stream().noneMatch(CompletableFuture::isDone));
        runner.release("t1");
        join(scheduler.onIdle());
    }

    @Test
    void runnerFailureContinuesQueue() {
        List<String> order = new CopyOnWriteArrayList<>();
        SubAgentRunner runner = item -> {
            order.add(item.taskId());
            if ("t1".equals(item.taskId())) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("boom"));
                return failed;
            }
            return CompletableFuture.completedFuture(null);
        };
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        join(scheduler.enqueue(item("t1", "e1", "same", "k1", "alpha")));
        join(scheduler.enqueue(item("t2", "e2", "same", "k2", "beta")));
        join(scheduler.scheduleOnce());
        join(scheduler.onIdle());
        assertEquals(List.of("sub-1"), subIds(scheduler));
        assertEquals(List.of("t1", "t2"), order);
        assertEquals("FAILED", scheduler.queue("sub-1").get(0).status());
        assertEquals("SUCCEEDED", scheduler.queue("sub-1").get(1).status());
    }

    @Test
    void missingRunnerFailsConstruction() {
        AgentException error = assertThrows(AgentException.class, () -> new QueueAgentScheduler(Map.of(), null));
        assertEquals(ErrorCode.INTERNAL, error.code());
    }

    @Test
    void duplicateOrBlankIdentityRejected() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        expectInternal(() -> join(scheduler.enqueue(item(" ", "e1", "s1", "k1", "alpha"))));
        expectInternal(() -> join(scheduler.enqueue(item("t1", " ", "s1", "k1", "alpha"))));
        assertTrue(scheduler.queue(QueueAgentScheduler.MAIN_ID).isEmpty());
        join(scheduler.enqueue(item("t1", "e1", "s1", "k1", "alpha")));
        expectInternal(() -> join(scheduler.enqueue(item("t1", "e2", "s2", "k2", "beta"))));
        assertEquals(List.of("t1"), ids(scheduler.queue(QueueAgentScheduler.MAIN_ID)));
    }

    @Test
    void concurrentScheduleAssignsEachItemOnce() throws Exception {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(SchedulerSettings.BATCH_SIZE, "4"), DONE);
        for (int index = 0; index < 4; index++) {
            join(scheduler.enqueue(separated("t" + index)));
        }
        CompletableFuture<ScheduleBatch> first = scheduler.scheduleOnce().toCompletableFuture();
        CompletableFuture<ScheduleBatch> second = scheduler.scheduleOnce().toCompletableFuture();
        ScheduleBatch left = first.get(5, TimeUnit.SECONDS);
        ScheduleBatch right = second.get(5, TimeUnit.SECONDS);
        assertEquals(4, left.taken() + right.taken());
        List<String> assigned = new ArrayList<>();
        left.assigned().forEach(snapshot -> assigned.add(snapshot.taskId()));
        right.assigned().forEach(snapshot -> assigned.add(snapshot.taskId()));
        assertEquals(4, assigned.size());
        assertEquals(4, new HashSet<>(assigned).size());
        assertTrue(scheduler.queue(QueueAgentScheduler.MAIN_ID).isEmpty());
        join(scheduler.onIdle());
    }

    @Test
    void coreStaysOnApi() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("jasagent-api"));
        assertClean(pom);
        Path src = Path.of("src/main/java");
        assertTrue(Files.isDirectory(src));
        try (Stream<Path> walk = Files.walk(src)) {
            List<Path> files = walk.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(files.isEmpty());
            for (Path file : files) {
                assertClean(Files.readString(file));
            }
        }
    }

    private static void assertDefaults(SchedulerSettings settings) {
        assertEquals(4, settings.batchSize());
        assertEquals(0.6, settings.mergeThreshold(), 1e-9);
        assertEquals(8, settings.queueCap());
    }

    private static void assertClean(String text) {
        String lower = text.toLowerCase();
        assertFalse(lower.contains("springframework"));
        assertFalse(lower.contains("spring-ai"));
        assertFalse(lower.contains("langgraph"));
        assertFalse(lower.contains("langchain"));
        assertFalse(text.contains("dev.jasagent.workflow"));
        assertFalse(text.contains("dev.jasagent.tool"));
        assertFalse(text.contains("dev.jasagent.plugin"));
        assertFalse(text.contains("dev.jasagent.web"));
    }

    private static WorkItem separated(String taskId) {
        return item(taskId, "e-" + taskId, "s-" + taskId, "k-" + taskId, taskId + " unique");
    }

    private static WorkItem item(String taskId, String executionId, String sessionId, String key, String text) {
        return new WorkItem(taskId, executionId, sessionId, key, text, "answer-with-tool", text);
    }

    private static List<String> ids(List<TaskSnapshot> queue) {
        return queue.stream().map(TaskSnapshot::taskId).toList();
    }

    private static List<String> subIds(QueueAgentScheduler scheduler) {
        return scheduler.agentIds().stream().filter(id -> !QueueAgentScheduler.MAIN_ID.equals(id)).toList();
    }

    private static String agentOf(QueueAgentScheduler scheduler, String taskId) {
        for (String agentId : scheduler.agentIds()) {
            for (TaskSnapshot snapshot : scheduler.queue(agentId)) {
                if (taskId.equals(snapshot.taskId())) {
                    return agentId;
                }
            }
        }
        fail("missing " + taskId);
        return "";
    }

    private static void awaitEntered(GateRunner runner, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runner.entered.get() < count) {
            if (System.nanoTime() > deadline) {
                fail("entered " + runner.entered.get());
            }
            Thread.onSpinWait();
        }
    }

    private static void expectInternal(ThrowingCall call) {
        RuntimeException thrown = assertThrows(RuntimeException.class, call::run);
        assertEquals(ErrorCode.INTERNAL, agent(thrown).code());
    }

    private static AgentException agent(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        assertTrue(current instanceof AgentException);
        return (AgentException) current;
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
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

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static final class GateRunner implements SubAgentRunner {
        final AtomicInteger entered = new AtomicInteger();
        final AtomicInteger inflight = new AtomicInteger();
        final AtomicInteger maxInflight = new AtomicInteger();
        final List<String> order = new CopyOnWriteArrayList<>();
        final Map<String, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();

        @Override
        public CompletionStage<Void> run(WorkItem item) {
            order.add(item.taskId());
            int now = inflight.incrementAndGet();
            maxInflight.accumulateAndGet(now, Math::max);
            entered.incrementAndGet();
            CompletableFuture<Void> gate = gates.computeIfAbsent(item.taskId(), id -> new CompletableFuture<>());
            return gate.whenComplete((ok, error) -> inflight.decrementAndGet());
        }

        void release(String taskId) {
            gates.computeIfAbsent(taskId, id -> new CompletableFuture<>()).complete(null);
        }
    }
}
