package dev.jasagent.interrupt;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.persist.DataDirectory;
import dev.jasagent.api.event.TraceEvent;
import dev.jasagent.persist.CheckpointBackend;
import dev.jasagent.persist.CheckpointStores;
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

/**
 * 用检查点驱动一条节点序列：开始时写入运行状态并逐步执行，中断时取消正在跑的阶段并记为挂起，恢复时从下一节点继续。
 * MySQL 的线程编号会加 run: 前缀，避免和别的用途撞号。
 */
public final class CheckpointRunControl implements RunControl, AutoCloseable {
    /** 图还没进入第一个业务节点时，检查点上记录的当前节点标记。 */
    private static final String START_NODE = "__START__";
    /** 最后一个业务节点完成后的下一节点标记，用来把执行收成成功。 */
    private static final String END_NODE = "__END__";

    private final BaseCheckpointSaver saver;
    private final Path sqliteFile;
    private final String threadPrefix;
    private final List<NodeTask> nodes;
    private final AgentTracer tracer;
    private final TraceWriter trace;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();

    /**
     * 记下存储器、SQLite 文件、线程前缀、节点序列、追踪器和事件写入器。
     *
     * @param saver 已打开的检查点存储器
     * @param sqliteFile SQLite 库文件，其它后端为空
     * @param threadPrefix 线程编号前缀，MySQL 为 run:，其它为空串
     * @param nodes 按顺序执行的节点，编号已经校验过
     * @param tracer 用来开根 span 和节点子 span
     * @param trace 用来写中断和恢复事件
     */
    private CheckpointRunControl(
            BaseCheckpointSaver saver,
            Path sqliteFile,
            String threadPrefix,
            List<NodeTask> nodes,
            AgentTracer tracer,
            TraceWriter trace) {
        this.saver = saver;
        this.sqliteFile = sqliteFile;
        this.threadPrefix = threadPrefix;
        this.nodes = nodes;
        this.tracer = tracer;
        this.trace = trace;
    }

    /**
     * 在工作区数据目录准备检查点库，按环境打开存储器，并校验节点编号非空、不重复、也不是起止标记。
     * SQLite 记下库文件；MySQL 的线程前缀是 run:。打不开时抛出持久化异常。
     *
     * @param workspace 工作区根目录
     * @param nodes 要按顺序执行的节点
     * @param tracer 执行和节点的追踪器
     * @param trace 中断与恢复事件的写入器
     * @param environment 进程环境，空时按没有配置处理
     * @return 可以开始、中断和恢复执行的控制器
     */
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
        try {
            Path sqlite = DataDirectory.of(workspace).resolve("checkpoints.db");
            Path data = sqlite.getParent();
            if (data != null) {
                Files.createDirectories(data);
            }
            var selection = CheckpointStores.select(env).withSqlite(sqlite);
            BaseCheckpointSaver saver = CheckpointStores.open(selection, new ObjectStreamStateSerializer<>(AgentState::new));
            Path file = selection.backend() == CheckpointBackend.SQLITE ? sqlite.toAbsolutePath() : null;
            String prefix = selection.backend() == CheckpointBackend.MYSQL ? "run:" : "";
            return new CheckpointRunControl(saver, file, prefix, plan, tracer, trace);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点存储器无法打开", null, ex);
        }
    }

    /**
     * 开一条根 span 后立刻结束，再写入用户、会话、工作区和运行中状态，然后从第一个节点执行。执行已存在时拒绝。空白会话编号改用执行编号。
     *
     * @param executionId 新执行的编号
     * @param userId 发起用户，以后中断和恢复都要拿它核对
     * @param sessionId 会话编号，空白时改用执行编号
     * @param workspace 这条执行所属的工作区路径
     * @return 跑完或中途停下时的执行快照
     */
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

    /**
     * 中断一条正在运行的执行。不是运行中则原样返回。运行中则记下取消、取消正在跑的阶段，并把检查点改成挂起、写下中断事件。调用方必须是这条执行的用户。
     *
     * @param executionId 要中断的执行编号
     * @param userId 操作者，必须与快照里的用户一致
     * @return 中断后的快照；本来就不是运行中时是当前快照
     */
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

    /**
     * 从挂起处继续。不是挂起则原样返回，不重复跑节点。挂起则把状态改回运行中、写下恢复事件，再从检查点里的下一节点执行。调用方必须是这条执行的用户。
     *
     * @param executionId 要恢复的执行编号
     * @param userId 操作者，必须与快照里的用户一致
     * @return 继续跑完或再次停下时的快照
     */
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

    /**
     * 返回底层检查点存储器，测试用它核对线程和检查点行。
     *
     * @return langgraph4j 存储器
     */
    public BaseCheckpointSaver saver() {
        return saver;
    }

    /**
     * 返回 SQLite 库文件。后端不是 SQLite 时为空。
     *
     * @return 库文件绝对路径，或空
     */
    public Path sqliteFile() {
        return sqliteFile;
    }

    /** 关闭占位，不释放底层存储器。 */
    @Override
    public void close() {
    }

    /**
     * 从给定下标起依次跑节点，某步返回快照就停；全部跑完则返回当前检查点对应的快照。离开时清掉取消标记和在途阶段。
     *
     * @param executionId 执行编号
     * @param startIndex 开始执行的节点下标，恢复时是被中断的那个节点
     * @return 停下或全部完成时的快照
     */
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

    /**
     * 跑一个节点。非运行中或已请求取消则挂起或返回当前快照。节点动作抛异常或返回空阶段则记失败。阶段被取消则挂起。正常完成则把产出追加到输出并前进，返回空表示继续。无论成败都结束子 span。
     *
     * @param executionId 执行编号
     * @param index 当前节点在序列里的下标
     * @return 需要停下来时的快照；还应继续下一节点时为空
     */
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

    /**
     * 把节点产出追加到输出，清掉错误码。下一节点是结束标记时把状态写成成功，否则保持运行中，然后提交检查点。
     *
     * @param executionId 执行编号
     * @param nodeId 刚完成的节点
     * @param nextNodeId 下一步节点，或结束标记
     * @param value 这个节点产出的文本
     */
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

    /**
     * 把执行记成挂起。已经是挂起则直接返回。否则清掉错误码、节点位置不变，并写下中断事件。
     *
     * @param executionId 执行编号
     * @param checkpoint 中断前读到的检查点
     * @return 挂起后的快照
     */
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

    /**
     * 恢复前把状态改回运行中并清掉错误码，节点位置保持不变。
     *
     * @param executionId 执行编号
     * @param checkpoint 挂起时的检查点
     * @return 改成运行中之后的检查点
     */
    private Checkpoint markRunning(String executionId, Checkpoint checkpoint) {
        Map<String, Object> state = copy(checkpoint.getState());
        state.put("status", ExecutionStatus.RUNNING.name());
        state.put("errorCode", "");
        return put(executionId, checkpoint.getNodeId(), checkpoint.getNextNodeId(), state);
    }

    /**
     * 把执行记成失败。包在完成异常里的业务异常用它自己的错误码，其它用内部错误。节点位置不变。
     *
     * @param executionId 执行编号
     * @param error 节点或阶段抛出的异常
     * @return 失败后的快照
     */
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

    /**
     * 按检查点里的工作区路径写入一条追踪事件，属性里带上当前状态，并等待写入完成。
     *
     * @param executionId 执行编号
     * @param checkpoint 提供工作区和状态的检查点
     * @param type 事件类型，中断或恢复
     * @param message 给人看的事件说明
     */
    private void emit(String executionId, Checkpoint checkpoint, String type, String message) {
        await(trace.event(
                Path.of(text(checkpoint.getState(), "workspace")),
                new TraceEvent(executionId, type, message, Instant.now(), Map.of("status", text(checkpoint.getState(), "status")))));
    }

    /**
     * 用新的检查点编号提交状态。线程编号带上打开时决定的前缀。提交失败时抛出带执行编号的持久化异常。
     *
     * @param executionId 执行编号
     * @param nodeId 检查点上的当前节点
     * @param nextNodeId 检查点上的下一节点
     * @param state 要保存的状态副本
     * @return 刚提交的检查点
     */
    private Checkpoint put(String executionId, String nodeId, String nextNodeId, Map<String, Object> state) {
        Checkpoint checkpoint = Checkpoint.builder()
                .id(UUID.randomUUID().toString())
                .nodeId(nodeId)
                .nextNodeId(nextNodeId)
                .state(new HashMap<>(state))
                .build();
        try {
            saver.put(RunnableConfig.builder().threadId(threadKey(executionId)).build(), checkpoint);
            return checkpoint;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点提交失败", executionId, ex);
        }
    }

    /**
     * 读取这条执行的最新检查点。还没有检查点时抛出执行不存在。
     *
     * @param executionId 执行编号
     * @return 最新检查点
     */
    private Checkpoint required(String executionId) {
        return find(executionId).orElseThrow(() -> new AgentException(ErrorCode.PERSIST, "执行不存在", executionId, null));
    }

    /**
     * 按带前缀的线程编号读取最近的检查点。运行时失败收成检查点读取失败。
     *
     * @param executionId 执行编号
     * @return 最近的检查点；还没有提交过时为空
     */
    private Optional<Checkpoint> find(String executionId) {
        try {
            return saver.get(RunnableConfig.builder().threadId(threadKey(executionId)).build());
        } catch (AgentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点读取失败", executionId, ex);
        }
    }

    /**
     * 在当前节点序列里找编号对应的下标。找不到说明检查点里的节点不在这次打开的流程中。
     *
     * @param executionId 执行编号，写进找不到节点时的异常
     * @param nodeId 检查点里的下一节点编号
     * @return 该节点在序列中的下标
     */
    private int indexOf(String executionId, String nodeId) {
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).id().equals(nodeId)) {
                return index;
            }
        }
        throw new AgentException(ErrorCode.WORKFLOW, "检查点里的节点不在当前流程中", executionId, null);
    }

    /**
     * 给执行编号加上打开时决定的前缀，作为检查点线程编号。
     *
     * @param executionId 执行编号
     * @return 存储器使用的线程编号
     */
    private String threadKey(String executionId) {
        return threadPrefix + executionId;
    }

    /**
     * 返回这条执行专用的锁，没有则创建，用来串行化同一条执行的检查点读写。
     *
     * @param executionId 执行编号
     * @return 该执行的锁对象
     */
    private Object lock(String executionId) {
        return locks.computeIfAbsent(executionId, key -> new Object());
    }

    /**
     * 核对操作者是不是这条执行的用户。对不上时抛出认证异常，避免改别人的执行。
     *
     * @param snapshot 当前执行快照
     * @param userId 操作者用户编号
     */
    private static void authorize(ExecutionSnapshot snapshot, String userId) {
        if (userId == null || !userId.equals(snapshot.userId())) {
            throw new AgentException(ErrorCode.AUTH, "不能操作其他用户的执行", snapshot.executionId().value(), null);
        }
    }

    /**
     * 用检查点状态里的用户、会话、工作区、状态、追踪和输出组装快照。空白错误码在快照里变成空。
     *
     * @param executionId 执行编号
     * @param checkpoint 提供状态的检查点
     * @return 给调用方看的执行快照
     */
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

    /**
     * 复制状态映射，避免改检查点里的原表。空映射按空表处理。
     *
     * @param state 检查点里的状态，可以为空
     * @return 可修改的副本
     */
    private static Map<String, Object> copy(Map<String, Object> state) {
        return new HashMap<>(state == null ? Map.of() : state);
    }

    /**
     * 把新产出接到已有输出后面，中间加换行。任一侧为空则返回另一侧。
     *
     * @param current 已经累积的输出
     * @param addition 这个节点新产出的文本
     * @return 拼接后的输出
     */
    private static String append(String current, String addition) {
        if (addition == null || addition.isEmpty()) {
            return current == null ? "" : current;
        }
        if (current == null || current.isEmpty()) {
            return addition;
        }
        return current + "\n" + addition;
    }

    /**
     * 从状态里取一段文本。状态或键缺失时返回空串。
     *
     * @param state 检查点状态，可以为空
     * @param key 状态键，例如 userId、status、output
     * @return 该键的文本，缺失时为空串
     */
    private static String text(Map<String, Object> state, String key) {
        Object value = state == null ? null : state.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 沿原因链查看这次失败是不是取消。用来区分用户中断和节点真的失败。
     *
     * @param error 阶段抛出的异常
     * @return 原因链上出现取消异常时为真
     */
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

    /**
     * 剥掉包在外面的完成异常，直到最内层原因，好取出业务错误码。
     *
     * @param error 可能包了好几层的异常
     * @return 最内层原因；没有原因时就是传入的异常
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 等待追踪或事件阶段结束。运行时异常和错误原样抛出，其它收成内部异常。
     *
     * @param <T> 阶段的结果类型
     * @param stage 要等待的阶段
     * @return 阶段的结果
     */
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

    /**
     * 打开前检查节点序列。列表不能为空；每个编号不能为空、不能是起止标记、不能重复。违反时抛出工作流异常。
     *
     * @param nodes 调用方传入的节点
     */
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
}
