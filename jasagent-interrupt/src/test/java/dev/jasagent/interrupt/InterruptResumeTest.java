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

class InterruptResumeTest {
    private static final String USER = "user-a";

    @TempDir
    Path temp;

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
                Map.of())) {
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
                Map.of())) {
            ExecutionSnapshot resumed = join(second.resume(executionId, USER));
            assertEquals(savedTrace, resumed.traceId());
            assertEquals(0, secondTracer.roots.get());
            assertFalse(secondTracer.childTraceIds.isEmpty());
            for (String traceId : secondTracer.childTraceIds) {
                assertEquals(savedTrace, traceId);
            }
        }
    }

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

    @Test
    void missingResumeFails() {
        String missing = id();
        try (CheckpointRunControl control = open(temp, List.of(node("only", ignored -> CompletableFuture.completedFuture("done"))))) {
            AgentException error = assertThrows(AgentException.class, () -> join(control.resume(missing, USER)));
            assertEquals(ErrorCode.PERSIST, error.code());
            assertEquals(missing, error.executionId());
        }
    }

    @Test
    void interruptEventRecorded() throws Exception {
        String executionId = id();
        MemoryTrace trace = new MemoryTrace();
        Hold hold = hold("slow");
        try (CheckpointRunControl control = CheckpointRunControl.open(temp, List.of(hold.task), new RecordingTracer("d".repeat(32)), trace, Map.of())) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            join(control.interrupt(executionId, USER));
            join(running);
            assertTrue(trace.events.stream().anyMatch(event -> "INTERRUPT".equals(event.type()) && executionId.equals(event.executionId())));
        }
    }

    @Test
    void resumeEventRecorded() throws Exception {
        String executionId = id();
        MemoryTrace trace = new MemoryTrace();
        Hold hold = hold("slow");
        try (CheckpointRunControl control = CheckpointRunControl.open(temp, List.of(hold.task), new RecordingTracer("e".repeat(32)), trace, Map.of())) {
            CompletableFuture<ExecutionSnapshot> running = control.start(executionId, USER, executionId, workspace()).toCompletableFuture();
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS));
            join(control.interrupt(executionId, USER));
            join(running);
            join(control.resume(executionId, USER));
            assertTrue(trace.events.stream().anyMatch(event -> "RESUME".equals(event.type()) && executionId.equals(event.executionId())));
        }
    }

    @Test
    void blankSessionIdUsesExecutionId() throws Exception {
        String executionId = id();
        try (CheckpointRunControl control = open(temp, List.of(node("only", ignored -> CompletableFuture.completedFuture("done"))))) {
            ExecutionSnapshot snapshot = join(control.start(executionId, USER, null, workspace()));
            assertEquals(executionId, snapshot.sessionId());
        }
    }

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

    private CheckpointRunControl open(Path workspace, List<NodeTask> nodes) {
        return CheckpointRunControl.open(workspace, nodes, new RecordingTracer("f".repeat(32)), new MemoryTrace(), Map.of());
    }

    private String workspace() {
        return temp.toAbsolutePath().toString();
    }

    private static Hold hold(String id) {
        return new Hold(id);
    }

    private static NodeTask node(String id, java.util.function.Function<String, CompletionStage<String>> action) {
        return NodeTask.of(id, action);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static Checkpoint latest(CheckpointRunControl control, String executionId) throws Exception {
        return control.saver().get(RunnableConfig.builder().threadId(executionId).build()).orElseThrow();
    }

    private static String threadName(Path sqlite, String executionId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath());
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT thread_name FROM LG4JThread WHERE thread_name = '" + executionId + "'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static int checkpointRows(Path sqlite, String executionId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath());
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM LG4JCheckpoint c JOIN LG4JThread t ON t.thread_id = c.thread_id WHERE t.thread_name = '" + executionId + "'")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

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

    private static final class Hold {
        final CompletableFuture<String> gate = new CompletableFuture<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final NodeTask task;

        Hold(String id) {
            this.task = node(id, ignored -> {
                entered.countDown();
                return gate;
            });
        }
    }

    private record FixedSpan(String name, String traceId, String spanId, String executionId) implements AgentSpan {
    }

    private static final class RecordingTracer implements AgentTracer {
        private final String traceId;
        private final AtomicInteger roots = new AtomicInteger();
        private final List<String> childTraceIds = new CopyOnWriteArrayList<>();

        RecordingTracer(String traceId) {
            this.traceId = traceId;
        }

        @Override
        public CompletionStage<AgentSpan> startRoot(String executionId) {
            roots.incrementAndGet();
            return CompletableFuture.completedFuture(new FixedSpan(SpanName.EXECUTION, traceId, "root-span", executionId));
        }

        @Override
        public CompletionStage<AgentSpan> startChild(String name, String executionId, String traceId, String parentSpanId) {
            childTraceIds.add(traceId);
            return CompletableFuture.completedFuture(new FixedSpan(name, traceId, "child-span", executionId));
        }

        @Override
        public CompletionStage<Void> end(AgentSpan span) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class MemoryTrace implements TraceWriter {
        private final List<TraceEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> event(Path workspace, TraceEvent event) {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<String> readLog(Path workspace, String executionId) {
            return CompletableFuture.completedFuture("");
        }
    }
}
