package dev.jasagent.persist;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.persist.DataDirectory;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** 一次执行图使用的检查点存储器。按工作区里的检查点文件和环境变量打开 SQLite、MySQL 或 PostgreSQL，并在进入下一节点之前提交当前节点状态。 */
public final class NodeCheckpoints implements AutoCloseable {
    private final CheckpointBackend backend;
    private final BaseCheckpointSaver saver;
    private final Path sqliteFile;

    /**
     * 记下已打开的后端、存储器和 SQLite 文件。非 SQLite 时文件为空。
     *
     * @param backend 实际使用的检查点后端
     * @param saver langgraph4j 检查点存储器
     * @param sqliteFile SQLite 库文件的绝对路径，其它后端为空
     */
    private NodeCheckpoints(CheckpointBackend backend, BaseCheckpointSaver saver, Path sqliteFile) {
        this.backend = backend;
        this.saver = saver;
        this.sqliteFile = sqliteFile;
    }

    /**
     * 在工作区数据目录下打开检查点库。SQLite 文件固定放在数据目录的 checkpoints.db。打不开时抛出持久化异常。
     *
     * @param workspace 工作区根目录
     * @param environment 进程环境，用来选择后端
     * @return 已打开的检查点
     */
    public static NodeCheckpoints open(Path workspace, Map<String, String> environment) {
        try {
            Path sqlite = DataDirectory.of(workspace).resolve("checkpoints.db");
            CheckpointSelection selection = CheckpointStores.select(environment).withSqlite(sqlite);
            BaseCheckpointSaver saver = CheckpointStores.open(selection, new ObjectStreamStateSerializer<>(AgentState::new));
            Path file = selection.backend() == CheckpointBackend.SQLITE ? sqlite.toAbsolutePath() : null;
            return new NodeCheckpoints(selection.backend(), saver, file);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点存储器无法打开", null, ex);
        }
    }

    /**
     * 返回这次打开实际落到的数据库种类。
     *
     * @return SQLite、PostgreSQL 或 MySQL
     */
    public CheckpointBackend backend() {
        return backend;
    }

    /**
     * 返回底层检查点存储器，供图编译时挂上。
     *
     * @return langgraph4j 存储器
     */
    public BaseCheckpointSaver saver() {
        return saver;
    }

    /**
     * 返回 SQLite 库文件。后端不是 SQLite 时为空，表示没有在工作区落文件。
     *
     * @return 库文件绝对路径，或空
     */
    public Path sqliteFile() {
        return sqliteFile;
    }

    /**
     * 先把当前节点的状态写成检查点，写成功后才运行进入下一节点的动作。写失败时下一节点不会跑，并带上执行编号抛出持久化异常。
     *
     * @param executionId 执行编号，同时作为检查点线程编号
     * @param nodeId 刚完成、正在落盘的节点
     * @param nextNodeId 下一步要进入的节点
     * @param state 要保存的图状态
     * @param nextNode 落盘成功后执行的动作，可以为空
     * @return 完成后得到的运行配置，里面带已保存的检查点
     */
    public CompletionStage<RunnableConfig> commitBeforeNextNode(
            String executionId,
            String nodeId,
            String nextNodeId,
            Map<String, Object> state,
            Runnable nextNode) {
        return VirtualIo.supply(() -> {
            Checkpoint checkpoint = Checkpoint.builder()
                    .id(UUID.randomUUID().toString())
                    .nodeId(nodeId)
                    .nextNodeId(nextNodeId)
                    .state(state)
                    .build();
            RunnableConfig config = RunnableConfig.builder().threadId(executionId).build();
            try {
                RunnableConfig saved = saver.put(config, checkpoint);
                if (nextNode != null) {
                    nextNode.run();
                }
                return saved;
            } catch (Exception ex) {
                throw new AgentException(ErrorCode.PERSIST, "检查点提交失败", executionId, ex);
            }
        });
    }

    /**
     * 读取这条执行最近一次提交的检查点。
     *
     * @param executionId 执行编号
     * @return 最近的检查点；还没有提交过时为空
     */
    public CompletionStage<Optional<Checkpoint>> latest(String executionId) {
        return VirtualIo.supply(() -> saver.get(RunnableConfig.builder().threadId(executionId).build()));
    }

    /** 关闭占位。存储器本身没有要在这里释放的连接。 */
    @Override
    public void close() {
    }
}
