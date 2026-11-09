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

public final class NodeCheckpoints implements AutoCloseable {
    private final CheckpointBackend backend;
    private final BaseCheckpointSaver saver;
    private final Path sqliteFile;

    private NodeCheckpoints(CheckpointBackend backend, BaseCheckpointSaver saver, Path sqliteFile) {
        this.backend = backend;
        this.saver = saver;
        this.sqliteFile = sqliteFile;
    }

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

    public CheckpointBackend backend() {
        return backend;
    }

    public BaseCheckpointSaver saver() {
        return saver;
    }

    public Path sqliteFile() {
        return sqliteFile;
    }

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

    public CompletionStage<Optional<Checkpoint>> latest(String executionId) {
        return VirtualIo.supply(() -> saver.get(RunnableConfig.builder().threadId(executionId).build()));
    }

    @Override
    public void close() {
    }
}
