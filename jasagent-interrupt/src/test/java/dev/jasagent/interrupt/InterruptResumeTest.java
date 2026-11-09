package dev.jasagent.interrupt;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.exec.ExecutionSnapshot;
import dev.jasagent.api.exec.ExecutionStatus;
import dev.jasagent.api.event.TraceEvent;
import dev.jasagent.api.otel.AgentSpan;
import dev.jasagent.api.otel.AgentTracer;
import dev.jasagent.api.otel.SpanName;
import dev.jasagent.api.trace.TraceWriter;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.SQLiteSaverV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 核对检查点控制器的中断、恢复、权限、追踪编号和进程重启后的续跑。 */
class InterruptResumeTest {
    /** 测试里发起执行的用户。另一个用户用来核对越权恢复会被拒绝。 */
    private static final String USER = "user-a";

    @TempDir
    Path temp;

    /**
     * 中断应取消当前节点返回的未完成阶段，并且不会进入它后面的节点。
     *
     * @throws Exception 等待进入节点或中断失败时抛出
     */
    @Test
    void interruptCancelsCurrentStage() throws Exception {
        String executionId = id();
        CompletableFuture<String> gate = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger after = new AtomicInteger();
        List<NodeTask> nodes = List.of(
                node("fast", ignored -> CompletableFuture.completedFuture("fast-ok")),
                node("slow", ignored -> {
                    entered.countDown();
                    return gate;
                }),
                node("after", ignored -> {
                    after.incrementAndGet();
                    return CompletableFuture.completedFuture("after-ok");
                }));
        try (CheckpointRunControl control = open(temp, nodes)) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            join(control.interrupt(executionId, USER));
            join(running);
            assertTrue(gate.isCancelled());
            assertEquals(0, after.get());
        }
    }

    /**
     * 中断后检查点应停在上一个已完成节点，下一节点仍是被卡住的节点，输出只含已完成节点的文本，线程名等于执行编号。
     *
     * @throws Exception 等待进入节点、中断或读取检查点失败时抛出
     */
    @Test
    void interruptStopsAtLastCheckpoint() throws Exception {
        String executionId = id();
        CompletableFuture<String> gate = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        List<NodeTask> nodes = List.of(
                node("fast", ignored -> CompletableFuture.completedFuture("fast-ok")),
                node("slow", ignored -> {
                    entered.countDown();
                    return gate;
                }));
        try (CheckpointRunControl control = open(temp, nodes)) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            join(control.interrupt(executionId, USER));
            join(running);
            Checkpoint latest = latest(control, executionId);
            assertEquals("fast", latest.getNodeId());
            assertEquals("slow", latest.getNextNodeId());
            assertEquals("fast-ok", latest.getState().get("output"));
            assertEquals(executionId, threadName(control.sqliteFile(), executionId));
        }
    }

    /**
     * 中断返回的快照和 start 结束时的快照都应是挂起，执行编号保持不变。
     *
     * @throws Exception 等待进入节点或中断失败时抛出
     */
    @Test
    void suspendedStatusAfterInterrupt() throws Exception {
        String executionId = id();
        Hold hold = hold("slow");
        try (CheckpointRunControl control = open(temp, List.of(node("fast", ignored -> CompletableFuture.completedFuture("fast-ok")), hold.task))) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            ExecutionSnapshot suspended = join(control.interrupt(executionId, USER));
            ExecutionSnapshot finished = join(running);
            assertEquals(ExecutionStatus.SUSPENDED, suspended.status());
            assertEquals(executionId, suspended.executionId().value());
            assertEquals(ExecutionStatus.SUSPENDED, finished.status());
            assertEquals(executionId, finished.executionId().value());
        }
    }

    /**
     * 恢复应沿用同一执行编号。已完成节点不重跑，被中断的节点再跑一次，其后节点照常跑，输出按节点拼接。
     *
     * @throws Exception 中断、恢复或等待失败时抛出
     */
    @Test
    void resumeContinuesWithSameExecutionId() throws Exception {
        String executionId = id();
        AtomicInteger fast = new AtomicInteger();
        AtomicInteger slow = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        Hold hold = new Hold("slow");
        List<NodeTask> nodes = List.of(
                node("fast", ignored -> {
                    fast.incrementAndGet();
                    return CompletableFuture.completedFuture("fast-ok");
                }),
                node("slow", ignored -> {
                    int call = slow.incrementAndGet();
                    if (call == 1) {
                        hold.entered.countDown();
                        return hold.gate;
                    }
                    return CompletableFuture.completedFuture("slow-ok");
                }),
                node("after", ignored -> {
                    after.incrementAndGet();
                    return CompletableFuture.completedFuture("after-ok");
                }));
        try (CheckpointRunControl control = open(temp, nodes)) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            join(control.interrupt(executionId, USER));
            join(running);
            ExecutionSnapshot resumed = join(control.resume(executionId, USER));
            assertEquals(executionId, resumed.executionId().value());
            assertEquals(ExecutionStatus.SUCCEEDED, resumed.status());
            assertEquals(1, fast.get());
            assertEquals(2, slow.get());
            assertEquals(1, after.get());
            assertEquals("fast-ok\nslow-ok\nafter-ok", resumed.output());
        }
    }

    /**
     * 已经成功的执行再恢复时直接返回当前快照，节点不会再跑。
     *
     * @throws Exception 启动或恢复失败时抛出
     */
    @Test
    void resumeSucceededReturnsCurrentSnapshot() throws Exception {
        String executionId = id();
        AtomicInteger calls = new AtomicInteger();
        try (CheckpointRunControl control = open(temp, List.of(node("only", ignored -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("done");
        })))) {
            ExecutionSnapshot done = join(control.start(executionId, USER, executionId, workspace()));
            ExecutionSnapshot again = join(control.resume(executionId, USER));
            assertEquals(done, again);
            assertEquals(1, calls.get());
        }
    }

    /**
     * 已经失败的执行再恢复时保持失败和错误码，后面的节点不跑。
     *
     * @throws Exception 启动或恢复失败时抛出
     */
    @Test
    void resumeFailedReturnsCurrentSnapshot() throws Exception {
        String executionId = id();
        AtomicInteger later = new AtomicInteger();
        List<NodeTask> nodes = List.of(
                node("bad", ignored -> CompletableFuture.failedFuture(new AgentException(ErrorCode.TOOL, "工具失败", executionId, null))),
                node("later", ignored -> {
                    later.incrementAndGet();
                    return CompletableFuture.completedFuture("no");
                }));
        try (CheckpointRunControl control = open(temp, nodes)) {
            ExecutionSnapshot failed = join(control.start(executionId, USER, executionId, workspace()));
            ExecutionSnapshot again = join(control.resume(executionId, USER));
            assertEquals(ExecutionStatus.FAILED, failed.status());
            assertEquals(ExecutionStatus.FAILED, again.status());
            assertEquals("TOOL", again.errorCode());
            assertEquals(executionId, again.executionId().value());
            assertEquals(0, later.get());
        }
    }

    /**
     * 仍在运行时恢复不取消当前阶段，也不重复进入节点。阶段完成后执行成功。
     *
     * @throws Exception 等待进入节点或恢复失败时抛出
     */
    @Test
    void resumeRunningReturnsCurrentSnapshot() throws Exception {
        String executionId = id();
        Hold hold = hold("slow");
        AtomicInteger calls = new AtomicInteger();
        NodeTask slow = node("slow", ignored -> {
            calls.incrementAndGet();
            hold.entered.countDown();
            return hold.gate;
        });
        try (CheckpointRunControl control = open(temp, List.of(slow))) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            ExecutionSnapshot mid = join(control.resume(executionId, USER));
            assertEquals(ExecutionStatus.RUNNING, mid.status());
            assertEquals(executionId, mid.executionId().value());
            assertFalse(hold.gate.isCancelled());
            assertEquals(1, calls.get());
            hold.gate.complete("done");
            ExecutionSnapshot done = join(running);
            assertEquals(ExecutionStatus.SUCCEEDED, done.status());
            assertEquals(1, calls.get());
        }
    }

    /**
     * 关掉控制器再打开同一工作区后，应从被中断的节点继续，前面的节点不重跑。
     *
     * @throws Exception 中断、重开或恢复失败时抛出
     */
    @Test
    void resumeAfterProcessRestart() throws Exception {
        String executionId = id();
        Hold hold = hold("slow");
        Path workspace = temp;
        List<NodeTask> firstNodes = List.of(
                node("fast", ignored -> CompletableFuture.completedFuture("fast-ok")),
                node("slow", ignored -> {
                    hold.entered.countDown();
                    return hold.gate;
                }),
                node("after", ignored -> CompletableFuture.completedFuture("after-ok")));
        try (CheckpointRunControl first = open(workspace, firstNodes)) {
            CompletableFuture<ExecutionSnapshot> running = first.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            join(first.interrupt(executionId, USER));
            join(running);
        }
        AtomicInteger fast = new AtomicInteger();
        AtomicInteger slow = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        List<NodeTask> secondNodes = List.of(
                node("fast", ignored -> {
                    fast.incrementAndGet();
                    return CompletableFuture.completedFuture("fast-ok");
                }),
                node("slow", ignored -> {
                    slow.incrementAndGet();
                    return CompletableFuture.completedFuture("slow-ok");
                }),
                node("after", ignored -> {
                    after.incrementAndGet();
                    return CompletableFuture.completedFuture("after-ok");
                }));
        try (CheckpointRunControl second = open(workspace, secondNodes)) {
            ExecutionSnapshot resumed = join(second.resume(executionId, USER));
            assertEquals(executionId, resumed.executionId().value());
            assertEquals(ExecutionStatus.SUCCEEDED, resumed.status());
            assertEquals(0, fast.get());
            assertEquals(1, slow.get());
            assertEquals(1, after.get());
        }
    }

    /**
     * 恢复后的子 span 应使用中断前保存的追踪编号，新的追踪器不再开根 span。
     *
     * @throws Exception 中断、恢复或读取追踪失败时抛出
     */
    @Test
    void resumedSpansUseSavedTraceId() throws Exception {
        String executionId = id();
        String savedTrace = "a".repeat(32);
        RecordingTracer firstTracer = new RecordingTracer(savedTrace);
        Hold hold = hold("slow");
        try (CheckpointRunControl first = CheckpointRunControl.open(
                temp,
                List.of(node("slow", ignored -> {
                    hold.entered.countDown();
                    return hold.gate;
                }), node("after", ignored -> CompletableFuture.completedFuture("after-ok"))),
                firstTracer,
                new MemoryTrace(),
                sqliteCheckpoint())) {
            CompletableFuture<ExecutionSnapshot> running = first.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            join(first.interrupt(executionId, USER));
            join(running);
            assertEquals(savedTrace, latest(first, executionId).getState().get("traceId"));
        }
        RecordingTracer secondTracer = new RecordingTracer("b".repeat(32));
        try (CheckpointRunControl second = CheckpointRunControl.open(
                temp,
                List.of(
                        node("slow", ignored -> CompletableFuture.completedFuture("slow-ok")),
                        node("after", ignored -> CompletableFuture.completedFuture("after-ok"))),
                secondTracer,
                new MemoryTrace(),
                sqliteCheckpoint())) {
            ExecutionSnapshot resumed = join(second.resume(executionId, USER));
            assertEquals(savedTrace, resumed.traceId());
            assertEquals(0, secondTracer.roots.get());
            assertFalse(secondTracer.childTraceIds.isEmpty());
            for (String traceId : secondTracer.childTraceIds) {
                assertEquals(savedTrace, traceId);
            }
        }
    }

    /**
     * 工作区根上已有名为 jasagent 的文件时，检查点仍应写到隐藏数据目录，且不把该文件改成目录。
     *
     * @throws Exception 启动或检查文件失败时抛出
     */
    @Test
    void checkpointsOpenWhenJasagentIsAFile() throws Exception {
        Files.writeString(temp.resolve("jasagent"), "#!/bin/sh\n");
        String executionId = id();
        try (CheckpointRunControl control = open(temp, List.of(node("only", ignored -> CompletableFuture.completedFuture("done"))))) {
            join(control.start(executionId, USER, executionId, workspace()));
            Path sqlite = temp.resolve(".jasagent").resolve("data").resolve("checkpoints.db");
            assertEquals(sqlite.toAbsolutePath(), control.sqliteFile());
            assertTrue(Files.isRegularFile(sqlite));
            assertTrue(checkpointRows(sqlite, executionId) > 0);
        }
        assertTrue(Files.isRegularFile(temp.resolve("jasagent")));
        assertFalse(Files.isDirectory(temp.resolve("jasagent")));
    }

    /**
     * 检查点应落在 SQLite 存储器的表里，不另建名为 node_checkpoint 的表。
     *
     * @throws Exception 启动或查询表名失败时抛出
     */
    @Test
    void checkpointsStayOnLanggraphSaver() throws Exception {
        String executionId = id();
        try (CheckpointRunControl control = open(temp, List.of(node("only", ignored -> CompletableFuture.completedFuture("done"))))) {
            join(control.start(executionId, USER, executionId, workspace()));
            Path sqlite = temp.resolve(".jasagent").resolve("data").resolve("checkpoints.db");
            assertInstanceOf(SQLiteSaverV2.class, control.saver());
            assertEquals(sqlite.toAbsolutePath(), control.sqliteFile());
            assertTrue(checkpointRows(sqlite, executionId) > 0);
            assertFalse(tableNames(sqlite).contains("node_checkpoint"));
        }
    }

    /** PostgreSQL 地址应去打开 Postgres 存储器。连不上时失败，且不创建 SQLite 检查点文件。 */
    @Test
    void postgresUrlSelectsPostgresSaver() {
        AgentException error = assertThrows(AgentException.class, () -> CheckpointRunControl.open(
                temp,
                List.of(node("only", ignored -> CompletableFuture.completedFuture("done"))),
                new RecordingTracer("c".repeat(32)),
                new MemoryTrace(),
                Map.of("JASAGENT_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/jasagent")));
        assertEquals(ErrorCode.PERSIST, error.code());
        assertTrue(stackOf(error).contains("org.bsc.langgraph4j.checkpoint.PostgresSaverV2"), stackOf(error));
        assertFalse(Files.exists(temp.resolve(".jasagent").resolve("data").resolve("checkpoints.db")));
    }

    /**
     * 别的用户恢复应得到认证错误，原执行保持挂起。
     *
     * @throws Exception 中断或等待失败时抛出
     */
    @Test
    void otherUserCannotResume() throws Exception {
        String executionId = id();
        Hold hold = hold("slow");
        try (CheckpointRunControl control = open(temp, List.of(hold.task))) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            join(control.interrupt(executionId, USER));
            join(running);
            AgentException error = assertThrows(AgentException.class, () -> join(control.resume(executionId, "user-b")));
            assertEquals(ErrorCode.AUTH, error.code());
            assertEquals("SUSPENDED", latest(control, executionId).getState().get("status"));
        }
    }

    /** 核对没有检查点的执行编号再恢复时得到持久化错误，并带回该编号。 */
    @Test
    void missingResumeFails() {
        String missing = id();
        try (CheckpointRunControl control = open(temp, List.of(node("only", ignored -> CompletableFuture.completedFuture("done"))))) {
            AgentException error = assertThrows(AgentException.class, () -> join(control.resume(missing, USER)));
            assertEquals(ErrorCode.PERSIST, error.code());
            assertEquals(missing, error.executionId());
        }
    }

    /**
     * 中断应写入类型为中断的追踪事件，并带上同一执行编号。
     *
     * @throws Exception 等待进入节点或中断失败时抛出
     */
    @Test
    void interruptEventRecorded() throws Exception {
        String executionId = id();
        MemoryTrace trace = new MemoryTrace();
        Hold hold = hold("slow");
        try (CheckpointRunControl control = CheckpointRunControl.open(temp, List.of(hold.task), new RecordingTracer("d".repeat(32)), trace, sqliteCheckpoint())) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            join(control.interrupt(executionId, USER));
            join(running);
            assertTrue(trace.events.stream().anyMatch(event -> "INTERRUPT".equals(event.type()) && executionId.equals(event.executionId())));
        }
    }

    /**
     * 恢复应写入类型为恢复的追踪事件，并带上同一执行编号。
     *
     * @throws Exception 中断、恢复或等待失败时抛出
     */
    @Test
    void resumeEventRecorded() throws Exception {
        String executionId = id();
        MemoryTrace trace = new MemoryTrace();
        Hold hold = hold("slow");
        try (CheckpointRunControl control = CheckpointRunControl.open(temp, List.of(hold.task), new RecordingTracer("e".repeat(32)), trace, sqliteCheckpoint())) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            join(control.interrupt(executionId, USER));
            join(running);
            join(control.resume(executionId, USER));
            assertTrue(trace.events.stream().anyMatch(event -> "RESUME".equals(event.type()) && executionId.equals(event.executionId())));
        }
    }

    /**
     * 核对会话编号为空时，快照里的会话编号等于执行编号。
     *
     * @throws Exception 启动失败时抛出
     */
    @Test
    void blankSessionIdUsesExecutionId() throws Exception {
        String executionId = id();
        try (CheckpointRunControl control = open(temp, List.of(node("only", ignored -> CompletableFuture.completedFuture("done"))))) {
            ExecutionSnapshot snapshot = join(control.start(executionId, USER, null, workspace()));
            assertEquals(executionId, snapshot.sessionId());
        }
    }

    /**
     * 对已经成功的执行再中断，应返回原来的快照，状态和输出都不变。
     *
     * @throws Exception 启动或中断失败时抛出
     */
    @Test
    void interruptOfFinishedReturnsSnapshot() throws Exception {
        String executionId = id();
        try (CheckpointRunControl control = open(temp, List.of(node("only", ignored -> CompletableFuture.completedFuture("done"))))) {
            ExecutionSnapshot done = join(control.start(executionId, USER, executionId, workspace()));
            ExecutionSnapshot again = join(control.interrupt(executionId, USER));
            assertEquals(ExecutionStatus.SUCCEEDED, again.status());
            assertEquals(done.executionId(), again.executionId());
            assertEquals(done.output(), again.output());
        }
    }

    /**
     * 测试用的检查点环境，强制走 SQLite。
     *
     * @return 只含 sqlite 模式的环境
     */
    private static Map<String, String> sqliteCheckpoint() {
        return Map.of("JASAGENT_CHECKPOINT", "sqlite");
    }

    /**
     * 用固定追踪器和内存事件打开控制器，检查点强制走 SQLite。
     *
     * @param workspace 工作区
     * @param nodes 这次测试的节点序列
     * @return 已打开的控制器
     */
    private CheckpointRunControl open(Path workspace, List<NodeTask> nodes) {
        return CheckpointRunControl.open(workspace, nodes, new RecordingTracer("f".repeat(32)), new MemoryTrace(), sqliteCheckpoint());
    }

    /**
     * 把临时目录的绝对路径当作工作区字符串，写进执行状态。
     *
     * @return 临时目录的绝对路径
     */
    private String workspace() {
        return temp.toAbsolutePath().toString();
    }

    /**
     * 做一个会停在未完成阶段上的节点，用来卡住执行再中断或恢复。
     *
     * @param id 节点编号
     * @return 带闩和未完成阶段的节点夹具
     */
    private static Hold hold(String id) {
        return new Hold(id);
    }

    /**
     * 按编号和动作组装一个测试节点。
     *
     * @param id 节点编号
     * @param action 输入执行编号、输出一段文本的异步动作
     * @return 新节点
     */
    private static NodeTask node(String id, java.util.function.Function<String, CompletionStage<String>> action) {
        return NodeTask.of(id, action);
    }

    /**
     * 生成一条测试执行的编号。
     *
     * @return 随机执行编号
     */
    private static String id() {
        return UUID.randomUUID().toString();
    }

    /**
     * 读取这条执行最近的检查点，没有时让测试失败。
     *
     * @param control 已打开的控制器
     * @param executionId 执行编号
     * @return 最近的检查点
     * @throws Exception 读取失败时抛出
     */
    private static Checkpoint latest(CheckpointRunControl control, String executionId) throws Exception {
        return control.saver().get(RunnableConfig.builder().threadId(executionId).build()).orElseThrow();
    }

    /**
     * 从 SQLite 里读出与执行编号相同的线程名，确认没有被加前缀。
     *
     * @param sqlite 检查点库文件
     * @param executionId 执行编号，也是期望的线程名
     * @return 查到的线程名
     * @throws Exception 连接或查询失败时抛出
     */
    private static String threadName(Path sqlite, String executionId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath());
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT thread_name FROM LG4JThread WHERE thread_name = '" + executionId + "'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    /**
     * 统计这条执行在 SQLite 检查点表里的行数。
     *
     * @param sqlite 检查点库文件
     * @param executionId 执行编号
     * @return 检查点行数
     * @throws Exception 连接或查询失败时抛出
     */
    private static int checkpointRows(Path sqlite, String executionId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath());
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM LG4JCheckpoint c JOIN LG4JThread t ON t.thread_id = c.thread_id WHERE t.thread_name = '" + executionId + "'")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    /**
     * 列出 SQLite 库里的表名，统一成小写，用来确认没有自建检查点表。
     *
     * @param sqlite 检查点库文件
     * @return 小写表名
     * @throws Exception 连接或查询失败时抛出
     */
    private static List<String> tableNames(Path sqlite) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath());
             ResultSet rows = connection.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rows.next()) {
                names.add(rows.getString(1).toLowerCase());
            }
        }
        return names;
    }

    /**
     * 把异常和它的原因链、栈展开成一段文本，用来确认失败发生在 Postgres 存储器。
     *
     * @param error 被断言抛出的异常
     * @return 异常链和栈的文本
     */
    private static String stackOf(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            builder.append(current).append('\n');
            for (StackTraceElement element : current.getStackTrace()) {
                builder.append(element).append('\n');
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    /**
     * 在三十秒内等待异步阶段结束。运行时异常和错误原样抛出；被中断时会重新置上当前线程的中断标记。
     *
     * @param <T> 阶段的结果类型
     * @param stage 要等待的阶段
     * @return 阶段的结果
     */
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

    /** 一个会停在未完成阶段上的节点，测试用它卡住执行再中断或恢复。 */
    private static final class Hold {
        final CompletableFuture<String> gate = new CompletableFuture<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final NodeTask task;

        /**
         * 用给定编号做一个节点：进入时放开闩，并返回尚未完成的阶段。
         *
         * @param id 节点编号
         */
        Hold(String id) {
            this.task = node(id, ignored -> {
                entered.countDown();
                return gate;
            });
        }
    }

    /**
     * 测试里固定追踪编号和 span 编号的 span，不连接真实的追踪后端。
     *
     * @param name span 名称
     * @param traceId 追踪编号
     * @param spanId span 编号
     * @param executionId 所属执行编号
     */
    private record FixedSpan(String name, String traceId, String spanId, String executionId) implements AgentSpan {
    }

    /** 记下开了几次根 span，以及子 span 用过哪些追踪编号，用来确认恢复后仍沿用保存的追踪。 */
    private static final class RecordingTracer implements AgentTracer {
        private final String traceId;
        private final AtomicInteger roots = new AtomicInteger();
        private final List<String> childTraceIds = new CopyOnWriteArrayList<>();

        /**
         * 固定这条追踪器发出的根追踪编号。
         *
         * @param traceId 根 span 使用的追踪编号
         */
        RecordingTracer(String traceId) {
            this.traceId = traceId;
        }

        /**
         * 记一次根 span，并返回带固定追踪编号的 span。
         *
         * @param executionId 执行编号
         * @return 已完成的根 span
         */
        @Override
        public CompletionStage<AgentSpan> startRoot(String executionId) {
            roots.incrementAndGet();
            return CompletableFuture.completedFuture(new FixedSpan(SpanName.EXECUTION, traceId, "root-span", executionId));
        }

        /**
         * 记一次子 span，并把传入的追踪编号收进列表，不改成追踪器自己的编号。
         *
         * @param name span 名称
         * @param executionId 执行编号
         * @param traceId 父追踪编号，恢复场景里应是上次保存的编号
         * @param parentSpanId 父 span 编号
         * @return 已完成的子 span
         */
        @Override
        public CompletionStage<AgentSpan> startChild(String name, String executionId, String traceId, String parentSpanId) {
            childTraceIds.add(traceId);
            return CompletableFuture.completedFuture(new FixedSpan(name, traceId, "child-span", executionId));
        }

        /**
         * 结束 span 时不做上报，直接完成。
         *
         * @param span 要结束的 span
         * @return 已完成的空阶段
         */
        @Override
        public CompletionStage<Void> end(AgentSpan span) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 把追踪事件留在内存里，测试据此确认中断和恢复有没有被记下来。 */
    private static final class MemoryTrace implements TraceWriter {
        private final List<TraceEvent> events = new CopyOnWriteArrayList<>();

        /**
         * 把事件追加到内存列表，不写工作区文件。
         *
         * @param workspace 工作区，这里不落盘
         * @param event 要记录的事件
         * @return 已完成的空阶段
         */
        @Override
        public CompletionStage<Void> event(Path workspace, TraceEvent event) {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 不保存日志正文，读取时返回空串。
         *
         * @param workspace 工作区
         * @param executionId 执行编号
         * @return 已完成的空串
         */
        @Override
        public CompletionStage<String> readLog(Path workspace, String executionId) {
            return CompletableFuture.completedFuture("");
        }
    }
}
