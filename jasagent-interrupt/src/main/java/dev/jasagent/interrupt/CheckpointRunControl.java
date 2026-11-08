package dev.jasagent.interrupt;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.event.TraceEvent;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.exec.ExecutionId;
import dev.jasagent.api.exec.ExecutionSnapshot;
import dev.jasagent.api.exec.ExecutionStatus;
import dev.jasagent.api.interrupt.RunControl;
import dev.jasagent.api.otel.AgentSpan;
import dev.jasagent.api.otel.AgentTracer;
import dev.jasagent.api.otel.SpanName;
import dev.jasagent.api.trace.TraceWriter;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.checkpoint.SQLiteSaverV2;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class CheckpointRunControl implements RunControl, AutoCloseable {
    private static final String START_NODE = "__START__";
    private static final String END_NODE = "__END__";

    private final BaseCheckpointSaver saver;
    private final Path sqliteFile;
    private final List<NodeTask> nodes;
    private final AgentTracer tracer;
    private final TraceWriter trace;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();

    private CheckpointRunControl(
            BaseCheckpointSaver saver,
            Path sqliteFile,
            List<NodeTask> nodes,
            AgentTracer tracer,
            TraceWriter trace) {
        this.saver = saver;
        this.sqliteFile = sqliteFile;
        this.nodes = nodes;
        this.tracer = tracer;
        this.trace = trace;
    }

    public static CheckpointRunControl open(
            Path workspace,
            List<NodeTask> nodes,
            AgentTracer tracer,
            TraceWriter trace,
            Map<String, String> environment) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(tracer, "tracer");
        Objects.requireNonNull(trace, "trace");
        List<NodeTask> plan = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        validate(plan);
        Map<String, String> env = environment == null ? Map.of() : environment;
        String jdbcUrl = env.get("JASAGENT_JDBC_URL");
        try {
            ObjectStreamStateSerializer<AgentState> serializer = new ObjectStreamStateSerializer<>(AgentState::new);
            if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:")) {
                PostgresEndpoint endpoint = PostgresEndpoint.parse(jdbcUrl);
                BaseCheckpointSaver postgres = PostgresSaverV2.builder()
                        .host(endpoint.host())
                        .port(endpoint.port())
                        .database(endpoint.database())
                        .user(blank(env.get("JASAGENT_JDBC_USER")))
                        .password(env.get("JASAGENT_JDBC_PASSWORD") == null ? "" : env.get("JASAGENT_JDBC_PASSWORD"))
                        .property("connectTimeout", "2")
                        .property("loginTimeout", "2")
                        .property("socketTimeout", "2")
                        .stateSerializer(serializer)
                        .createTables(true)
                        .build();
                return new CheckpointRunControl(postgres, null, plan, tracer, trace);
            }
            Path sqlite = workspace.toAbsolutePath().resolve("jasagent").resolve("data").resolve("checkpoints.db");
            Files.createDirectories(sqlite.getParent());
            BaseCheckpointSaver sqliteSaver = SQLiteSaverV2.builder()
                    .databasePath(sqlite.toString())
                    .stateSerializer(serializer)
                    .createTables(true)
                    .build();
            return new CheckpointRunControl(sqliteSaver, sqlite, plan, tracer, trace);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点存储器无法打开", null, ex);
        }
    }

    public CompletionStage<ExecutionSnapshot> start(String executionId, String userId, String sessionId, String workspace) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workspace, "workspace");
        return VirtualIo.supply(() -> {
            AgentSpan root = await(tracer.startRoot(executionId));
            await(tracer.end(root));
            synchronized (lock(executionId)) {
                if (find(executionId).isPresent()) {
                    throw new AgentException(ErrorCode.INTERNAL, "执行已经存在", executionId, null);
                }
                Map<String, Object> state = new HashMap<>();
                state.put("userId", userId);
                state.put("sessionId", sessionId == null || sessionId.isBlank() ? executionId : sessionId);
                state.put("workspace", workspace);
                state.put("status", ExecutionStatus.RUNNING.name());
                state.put("traceId", root.traceId());
                state.put("rootSpanId", root.spanId());
                state.put("output", "");
                state.put("errorCode", "");
                put(executionId, START_NODE, nodes.get(0).id(), state);
            }
            return execute(executionId, 0);
        });
    }

    @Override
    public CompletionStage<ExecutionSnapshot> interrupt(String executionId, String userId) {
        return VirtualIo.supply(() -> {
            synchronized (lock(executionId)) {
                Checkpoint checkpoint = required(executionId);
                ExecutionSnapshot current = toSnapshot(executionId, checkpoint);
                authorize(current, userId);
                if (current.status() != ExecutionStatus.RUNNING) {
                    return current;
                }
                cancelRequested.add(executionId);
                CompletableFuture<?> future = inflight.get(executionId);
                if (future != null) {
                    future.cancel(true);
                }
                return suspend(executionId, required(executionId));
            }
        });
    }

    @Override
    public CompletionStage<ExecutionSnapshot> resume(String executionId, String userId) {
        return VirtualIo.supply(() -> {
            int index;
            synchronized (lock(executionId)) {
                Checkpoint checkpoint = required(executionId);
                ExecutionSnapshot current = toSnapshot(executionId, checkpoint);
                authorize(current, userId);
                if (current.status() != ExecutionStatus.SUSPENDED) {
                    return current;
                }
                index = indexOf(executionId, checkpoint.getNextNodeId());
                Checkpoint running = markRunning(executionId, checkpoint);
                emit(executionId, running, "RESUME", "恢复");
            }
            return execute(executionId, index);
        });
    }

    public BaseCheckpointSaver saver() {
        return saver;
    }

    public Path sqliteFile() {
        return sqliteFile;
    }

    @Override
    public void close() {
    }

    private ExecutionSnapshot execute(String executionId, int startIndex) {
        try {
            for (int index = startIndex; index < nodes.size(); index++) {
                Optional<ExecutionSnapshot> stopped = step(executionId, index);
                if (stopped.isPresent()) {
                    return stopped.get();
                }
            }
            synchronized (lock(executionId)) {
                return toSnapshot(executionId, required(executionId));
            }
        } finally {
            cancelRequested.remove(executionId);
            inflight.remove(executionId);
        }
    }

    private Optional<ExecutionSnapshot> step(String executionId, int index) {
        AgentSpan span = null;
        try {
            CompletableFuture<String> future;
            synchronized (lock(executionId)) {
                Checkpoint checkpoint = required(executionId);
                ExecutionSnapshot current = toSnapshot(executionId, checkpoint);
                if (current.status() != ExecutionStatus.RUNNING || cancelRequested.contains(executionId)) {
                    if (current.status() == ExecutionStatus.RUNNING) {
                        return Optional.of(suspend(executionId, checkpoint));
                    }
                    return Optional.of(current);
                }
                span = await(tracer.startChild(
                        SpanName.WORKFLOW_NODE,
                        executionId,
                        current.traceId(),
                        current.rootSpanId()));
                CompletionStage<String> stage;
                try {
                    stage = nodes.get(index).run(executionId);
                } catch (RuntimeException ex) {
                    return Optional.of(fail(executionId, ex));
                }
                if (stage == null) {
                    return Optional.of(fail(executionId, new AgentException(
                            ErrorCode.WORKFLOW, "节点没有返回阶段", executionId, null)));
                }
                future = stage.toCompletableFuture();
                inflight.put(executionId, future);
            }
            try {
                String value = future.join();
                synchronized (lock(executionId)) {
                    inflight.remove(executionId, future);
                    if (future.isCancelled() || cancelRequested.contains(executionId)) {
                        return Optional.of(suspend(executionId, required(executionId)));
                    }
                    String next = index + 1 < nodes.size() ? nodes.get(index + 1).id() : END_NODE;
                    advance(executionId, nodes.get(index).id(), next, value);
                    return Optional.empty();
                }
            } catch (CompletionException | CancellationException ex) {
                synchronized (lock(executionId)) {
                    inflight.remove(executionId, future);
                    if (future.isCancelled() || cancelled(ex) || cancelRequested.contains(executionId)) {
                        return Optional.of(suspend(executionId, required(executionId)));
                    }
                    return Optional.of(fail(executionId, ex));
                }
            }
        } finally {
            if (span != null) {
                await(tracer.end(span));
            }
        }
    }

    private void advance(String executionId, String nodeId, String nextNodeId, String value) {
        Checkpoint checkpoint = required(executionId);
        Map<String, Object> state = copy(checkpoint.getState());
        state.put("output", append(text(state, "output"), value));
        state.put("errorCode", "");
        state.put("status", END_NODE.equals(nextNodeId)
                ? ExecutionStatus.SUCCEEDED.name()
                : ExecutionStatus.RUNNING.name());
        put(executionId, nodeId, nextNodeId, state);
    }

    private ExecutionSnapshot suspend(String executionId, Checkpoint checkpoint) {
        ExecutionSnapshot current = toSnapshot(executionId, checkpoint);
        if (current.status() == ExecutionStatus.SUSPENDED) {
            return current;
        }
        Map<String, Object> state = copy(checkpoint.getState());
        state.put("status", ExecutionStatus.SUSPENDED.name());
        state.put("errorCode", "");
        Checkpoint saved = put(executionId, checkpoint.getNodeId(), checkpoint.getNextNodeId(), state);
        emit(executionId, saved, "INTERRUPT", "中止");
        return toSnapshot(executionId, saved);
    }

    private Checkpoint markRunning(String executionId, Checkpoint checkpoint) {
        Map<String, Object> state = copy(checkpoint.getState());
        state.put("status", ExecutionStatus.RUNNING.name());
        state.put("errorCode", "");
        return put(executionId, checkpoint.getNodeId(), checkpoint.getNextNodeId(), state);
    }

    private ExecutionSnapshot fail(String executionId, Throwable error) {
        Throwable unwrapped = unwrap(error);
        String code = unwrapped instanceof AgentException agent
                ? agent.code().name()
                : ErrorCode.INTERNAL.name();
        Checkpoint checkpoint = required(executionId);
        Map<String, Object> state = copy(checkpoint.getState());
        state.put("status", ExecutionStatus.FAILED.name());
        state.put("errorCode", code);
        Checkpoint saved = put(executionId, checkpoint.getNodeId(), checkpoint.getNextNodeId(), state);
        return toSnapshot(executionId, saved);
    }

    private void emit(String executionId, Checkpoint checkpoint, String type, String message) {
        await(trace.event(
                Path.of(text(checkpoint.getState(), "workspace")),
                new TraceEvent(executionId, type, message, Instant.now(), Map.of("status", text(checkpoint.getState(), "status")))));
    }

    private Checkpoint put(String executionId, String nodeId, String nextNodeId, Map<String, Object> state) {
        Checkpoint checkpoint = Checkpoint.builder()
                .id(UUID.randomUUID().toString())
                .nodeId(nodeId)
                .nextNodeId(nextNodeId)
                .state(new HashMap<>(state))
                .build();
        try {
            saver.put(RunnableConfig.builder().threadId(executionId).build(), checkpoint);
            return checkpoint;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点提交失败", executionId, ex);
        }
    }

    private Checkpoint required(String executionId) {
        return find(executionId).orElseThrow(() -> new AgentException(ErrorCode.PERSIST, "执行不存在", executionId, null));
    }

    private Optional<Checkpoint> find(String executionId) {
        try {
            return saver.get(RunnableConfig.builder().threadId(executionId).build());
        } catch (AgentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点读取失败", executionId, ex);
        }
    }

    private int indexOf(String executionId, String nodeId) {
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).id().equals(nodeId)) {
                return index;
            }
        }
        throw new AgentException(ErrorCode.WORKFLOW, "检查点里的节点不在当前流程中", executionId, null);
    }

    private Object lock(String executionId) {
        return locks.computeIfAbsent(executionId, key -> new Object());
    }

    private static void authorize(ExecutionSnapshot snapshot, String userId) {
        if (userId == null || !userId.equals(snapshot.userId())) {
            throw new AgentException(ErrorCode.AUTH, "不能操作其他用户的执行", snapshot.executionId().value(), null);
        }
    }

    private static ExecutionSnapshot toSnapshot(String executionId, Checkpoint checkpoint) {
        Map<String, Object> state = checkpoint.getState();
        String errorCode = text(state, "errorCode");
        return new ExecutionSnapshot(
                new ExecutionId(executionId),
                text(state, "userId"),
                text(state, "sessionId"),
                text(state, "workspace"),
                ExecutionStatus.valueOf(text(state, "status")),
                text(state, "traceId"),
                text(state, "rootSpanId"),
                text(state, "output"),
                errorCode.isBlank() ? null : errorCode);
    }

    private static Map<String, Object> copy(Map<String, Object> state) {
        return new HashMap<>(state == null ? Map.of() : state);
    }

    private static String append(String current, String addition) {
        if (addition == null || addition.isEmpty()) {
            return current == null ? "" : current;
        }
        if (current == null || current.isEmpty()) {
            return addition;
        }
        return current + "\n" + addition;
    }

    private static String text(Map<String, Object> state, String key) {
        Object value = state == null ? null : state.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static boolean cancelled(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException ex) {
            Throwable cause = unwrap(ex);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AgentException(ErrorCode.INTERNAL, cause.getMessage(), null, cause);
        }
    }

    private static void validate(List<NodeTask> nodes) {
        if (nodes.isEmpty()) {
            throw new AgentException(ErrorCode.WORKFLOW, "工作流没有节点");
        }
        Set<String> seen = new HashSet<>();
        for (NodeTask node : nodes) {
            if (node == null
                    || node.id() == null
                    || node.id().isBlank()
                    || START_NODE.equals(node.id())
                    || END_NODE.equals(node.id())
                    || !seen.add(node.id())) {
                throw new AgentException(ErrorCode.WORKFLOW, "节点标识不能为空或重复");
            }
        }
    }

    private record PostgresEndpoint(String host, int port, String database) {
        static PostgresEndpoint parse(String url) {
            String rest = url.substring("jdbc:postgresql://".length());
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            String database = slash < 0 ? "" : rest.substring(slash + 1);
            int query = database.indexOf('?');
            if (query >= 0) {
                database = database.substring(0, query);
            }
            int at = hostPort.lastIndexOf('@');
            if (at >= 0) {
                hostPort = hostPort.substring(at + 1);
            }
            String host = hostPort;
            int port = 5432;
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0 && hostPort.indexOf(':') == colon) {
                host = hostPort.substring(0, colon);
                port = Integer.parseInt(hostPort.substring(colon + 1));
            }
            return new PostgresEndpoint(host, port, database);
        }
    }
}
