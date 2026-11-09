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

/**
 * 核对队列调度的缺省参数、相关度合并、子代理串行与并行，以及失败后继续执行。
 */
@Timeout(20)
class QueueAgentTest {
    /**
     * 立即成功的运行器，给不关心执行过程的调度用例使用。
     */
    private static final SubAgentRunner DONE = item -> CompletableFuture.completedFuture(null);

    /**
     * 配置缺失或空映射时，批大小、合并门槛和队列上限分别是 4、0.6 和 8。断言失败则本测试失败。
     */
    @Test
    void defaultsWhenUnset() {
        assertDefaults(new QueueAgentScheduler(null, DONE).settings());
        assertDefaults(new QueueAgentScheduler(Map.of(), DONE).settings());
    }

    /**
     * 批大小、合并门槛和队列上限都可以被配置替换。断言失败则本测试失败。
     */
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

    /**
     * 批大小为 2 时一轮只取走两条，第三条留在主队列。断言失败则本测试失败。
     */
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

    /**
     * 批大小、门槛或上限不是合法数值时，构造调度器要报内部错误。断言失败则本测试失败。
     */
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

    /**
     * 会话标识相同的两条待办相关度为 1，即使目标和关联键不同。断言失败则本测试失败。
     */
    @Test
    void sameSessionScoresOne() {
        WorkItem left = item("t1", "e1", "same", "k1", "alpha beta");
        WorkItem right = item("t2", "e2", "same", "k2", "gamma delta");
        assertEquals(1.0, WorkCorrelation.score(left, right), 0.0);
    }

    /**
     * 会话不同但关联键相同的相关度是 0.9。断言失败则本测试失败。
     */
    @Test
    void sameCorrelationKeyScoresPointNine() {
        WorkItem left = item("t1", "e1", "s1", "shared", "same text");
        WorkItem right = item("t2", "e2", "s2", "shared", "same text");
        assertEquals(0.9, WorkCorrelation.score(left, right), 0.0);
    }

    /**
     * 没有相同会话和关联键时按目标文本的词交并比计算，完全不相交则为零。断言失败则本测试失败。
     */
    @Test
    void otherwiseTokenJaccard() {
        WorkItem apple = item("t1", "e1", "s1", "k1", "red apple");
        WorkItem banana = item("t2", "e2", "s2", "k2", "red banana");
        WorkItem alpha = item("t3", "e3", "s3", "k3", "alpha beta");
        WorkItem gamma = item("t4", "e4", "s4", "k4", "gamma delta");
        assertEquals(1.0 / 3.0, WorkCorrelation.score(apple, banana), 1e-9);
        assertEquals(0.0, WorkCorrelation.score(alpha, gamma), 0.0);
    }

    /**
     * 空白会话不算相同，文本也不相交时相关度为零。断言失败则本测试失败。
     */
    @Test
    void blankSessionDoesNotMatch() {
        WorkItem left = item("t1", "e1", " ", "k1", "alpha");
        WorkItem right = item("t2", "e2", "", "k2", "beta");
        assertEquals(0.0, WorkCorrelation.score(left, right), 0.0);
    }

    /**
     * 空白关联键不算相同。断言失败则本测试失败。
     */
    @Test
    void blankCorrelationKeyDoesNotMatch() {
        WorkItem left = item("t1", "e1", "s1", " ", "alpha");
        WorkItem right = item("t2", "e2", "s2", "", "beta");
        assertEquals(0.0, WorkCorrelation.score(left, right), 0.0);
    }

    /**
     * 会话和关联键区分大小写，大小写不同不能因此得到正的相关度。断言失败则本测试失败。
     */
    @Test
    void caseDifferenceDoesNotMatch() {
        WorkItem left = item("t1", "e1", "Session", "Key", "alpha");
        WorkItem right = item("t2", "e2", "session", "key", "beta");
        assertEquals(0.0, WorkCorrelation.score(left, right), 0.0);
    }

    /**
     * 目标文本比较时忽略大小写、标点和重复词，两侧都没有词时相关度为零。断言失败则本测试失败。
     */
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

    /**
     * 入队时会话为空白，保存后的会话要改成执行标识。断言失败则本测试失败。
     */
    @Test
    void blankSessionBecomesExecutionId() {
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), DONE);
        join(scheduler.enqueue(item("t1", "exec-blank", "  ", "k1", "alpha")));
        TaskSnapshot stored = scheduler.queue(QueueAgentScheduler.MAIN_ID).get(0);
        assertEquals("exec-blank", stored.item().sessionId());
    }

    /**
     * 入队后任务留在主队列，角色为主、状态为排队，运行器尚未被调用。断言失败则本测试失败。
     */
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

    /**
     * 主队列为空时一轮取走零条，也不调用运行器。断言失败则本测试失败。
     */
    @Test
    void emptyScheduleTakesNothing() {
        GateRunner runner = new GateRunner();
        QueueAgentScheduler scheduler = new QueueAgentScheduler(Map.of(), runner);
        ScheduleBatch batch = join(scheduler.scheduleOnce());
        assertEquals(0, batch.taken());
        assertTrue(batch.assigned().isEmpty());
        assertEquals(0, runner.entered.get());
    }

    /**
     * 缺省一轮最多取四条，下一轮再取走剩下的一条。断言失败则本测试失败。
     */
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

    /**
     * 同一会话的两条任务分到同一个子代理。断言失败则本测试失败。
     */
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

    /**
     * 相关度低于 0.6 的两条任务分到不同子代理。断言失败则本测试失败。
     */
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

    /**
     * 相关度正好等于 0.6 时合并到同一子代理。断言失败则本测试失败。
     */
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

    /**
     * 相邻任务达到门槛时整条链合并，即使链的两端彼此低于门槛；无关任务分开。断言失败则本测试失败。
     */
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

    /**
     * 合并到同一子代理后仍保持入队顺序。断言失败则本测试失败。
     */
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

    /**
     * 同会话子代理还有未完成任务时，新的同会话任务追加到它，不另开代理。断言失败则本测试失败。
     */
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

    /**
     * 新任务和多个未完成代理都相关时，追加到相关度更高的那个。断言失败则本测试失败。
     */
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

    /**
     * 相关度最高的代理已达上限时另开代理，不把任务塞进相关度更低的代理。断言失败则本测试失败。
     */
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

    /**
     * 同一组合并后的任务超过队列上限时，按顺序拆到新的子代理。断言失败则本测试失败。
     */
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

    /**
     * 已有未完成但文本不相关的代理不会接收新任务。断言失败则本测试失败。
     */
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

    /**
     * 已经成功结束的同会话代理不会再接收下一条。断言失败则本测试失败。
     */
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

    /**
     * 每个代理标识只出现一次，每条任务也只出现在一个队列里。断言失败则本测试失败。
     */
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

    /**
     * 同一子代理必须等上一条放行后才进入下一条，同时执行数不超过一。断言失败则本测试失败。
     */
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

    /**
     * 分到不同子代理的任务可以同时进入运行器。断言失败则本测试失败。
     */
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

    /**
     * 分派后的任务角色是子代理，真正执行时不再留在主队列。断言失败则本测试失败。
     */
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

    /**
     * 入队不执行任务；一轮调度在任务放行前就能返回，返回时状态仍是排队。断言失败则本测试失败。
     */
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

    /**
     * 第一条执行失败后，同一子代理继续跑第二条，并分别标成失败和成功。断言失败则本测试失败。
     */
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

    /**
     * 没有运行器时不能构造调度器，并报内部错误。断言失败则本测试失败。
     */
    @Test
    void missingRunnerFailsConstruction() {
        AgentException error = assertThrows(AgentException.class, () -> new QueueAgentScheduler(Map.of(), null));
        assertEquals(ErrorCode.INTERNAL, error.code());
    }

    /**
     * 空白任务标识、空白执行标识和重复任务标识都被拒绝，主队列只保留第一次成功入队的任务。断言失败则本测试失败。
     */
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

    /**
     * 两轮并发调度合起来分走全部四条，且每条只被分派一次。断言失败则本测试失败。
     */
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

    /**
     * 核心构建描述依赖 api 模块，源码中不能出现被禁止的外部框架或其他实现包。断言失败则本测试失败。
     */
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

    /**
     * 核对调度参数是否仍是批大小 4、合并门槛 0.6、队列上限 8。不符合时测试失败。
     *
     * @param settings 待核对的参数
     */
    private static void assertDefaults(SchedulerSettings settings) {
        assertEquals(4, settings.batchSize());
        assertEquals(0.6, settings.mergeThreshold(), 1e-9);
        assertEquals(8, settings.queueCap());
    }

    /**
     * 确认文本里没有被禁止的外部框架名或其他实现包名。不符合时测试失败。
     *
     * @param text 构建描述或源码全文
     */
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

    /**
     * 造一条会话、关联键和目标文本都与其他任务错开的待办。
     *
     * @param taskId 任务标识，也用来派生其余字段
     * @return 不会和别的分开任务合并的待办
     */
    private static WorkItem separated(String taskId) {
        return item(taskId, "e-" + taskId, "s-" + taskId, "k-" + taskId, taskId + " unique");
    }

    /**
     * 按给定标识和目标文本组装一条待办。
     *
     * @param taskId 任务标识
     * @param executionId 执行标识
     * @param sessionId 会话标识
     * @param key 关联键
     * @param text 用来计算相关度的目标文本
     * @return 供入队或直接比较的待办
     */
    private static WorkItem item(String taskId, String executionId, String sessionId, String key, String text) {
        return new WorkItem(taskId, executionId, sessionId, key, text, "answer-with-tool", text);
    }

    /**
     * 按队列当前顺序取出任务标识。
     *
     * @param queue 某个代理的任务快照
     * @return 任务标识列表
     */
    private static List<String> ids(List<TaskSnapshot> queue) {
        return queue.stream().map(TaskSnapshot::taskId).toList();
    }

    /**
     * 返回除主代理以外的代理标识。
     *
     * @param scheduler 已经分派过的调度器
     * @return 子代理标识
     */
    private static List<String> subIds(QueueAgentScheduler scheduler) {
        return scheduler.agentIds().stream().filter(id -> !QueueAgentScheduler.MAIN_ID.equals(id)).toList();
    }

    /**
     * 按任务标识找到它所在的代理；找不到时让测试失败。
     *
     * @param scheduler 要搜索的调度器
     * @param taskId 任务标识
     * @return 持有该任务的代理标识
     */
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

    /**
     * 空转到运行器进入次数达到期望，超时则测试失败。副作用是占用当前线程自旋等待。
     *
     * @param runner 记录进入次数的运行器
     * @param count 期望至少进入的次数
     */
    private static void awaitEntered(GateRunner runner, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runner.entered.get() < count) {
            if (System.nanoTime() > deadline) {
                fail("entered " + runner.entered.get());
            }
            Thread.onSpinWait();
        }
    }

    /**
     * 执行动作，并确认剥开包装后是内部错误。不符合时测试失败。
     *
     * @param call 应当失败的动作
     */
    private static void expectInternal(ThrowingCall call) {
        RuntimeException thrown = assertThrows(RuntimeException.class, call::run);
        assertEquals(ErrorCode.INTERNAL, agent(thrown).code());
    }

    /**
     * 剥掉完成异常和执行异常，得到里面的业务异常。
     *
     * @param error 测试捕获到的异常
     * @return 链上的业务异常
     */
    private static AgentException agent(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        assertTrue(current instanceof AgentException);
        return (AgentException) current;
    }

    /**
     * 在限定时间内取回异步结果，并把包装异常还原成原来的运行时异常；等待被中断时重新置上中断标记。
     *
     * @param stage 待等待的异步阶段
     * @return 阶段成功时的结果
     */
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

    /**
     * 包一层会抛出运行时异常的测试动作，便于断言入队被拒绝。
     */
    @FunctionalInterface
    private interface ThrowingCall {
        /**
         * 执行测试放进来的动作。副作用由该动作自己决定。
         */
        void run();
    }

    /**
     * 挡住每个任务直到测试放行，并记录进入顺序和同时执行的数量。
     */
    private static final class GateRunner implements SubAgentRunner {
        final AtomicInteger entered = new AtomicInteger();
        final AtomicInteger inflight = new AtomicInteger();
        final AtomicInteger maxInflight = new AtomicInteger();
        final List<String> order = new CopyOnWriteArrayList<>();
        final Map<String, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();

        /**
         * 记录任务进入，增加正在执行的数量，然后等到该任务被放行。副作用是更新进入顺序和并发计数。
         *
         * @param item 调度器交来的待办
         * @return 该任务被放行后完成的阶段
         */
        @Override
        public CompletionStage<Void> run(WorkItem item) {
            order.add(item.taskId());
            int now = inflight.incrementAndGet();
            maxInflight.accumulateAndGet(now, Math::max);
            entered.incrementAndGet();
            CompletableFuture<Void> gate = gates.computeIfAbsent(item.taskId(), id -> new CompletableFuture<>());
            return gate.whenComplete((ok, error) -> inflight.decrementAndGet());
        }

        /**
         * 放行指定任务，使它的等待阶段完成。副作用是完成对应的门。
         *
         * @param taskId 要放行的任务标识
         */
        void release(String taskId) {
            gates.computeIfAbsent(taskId, id -> new CompletableFuture<>()).complete(null);
        }
    }
}
