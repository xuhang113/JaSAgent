package dev.jasagent.persist;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.persist.DataDirectory;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.checkpoint.SQLiteSaverV2;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.nio.file.Files;
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
        JdbcSettings settings = JdbcSettings.resolve(workspace, environment);
        try {
            if (settings.postgresql()) {
                PostgresEndpoint endpoint = PostgresEndpoint.parse(settings.url());
                BaseCheckpointSaver saver = PostgresSaverV2.builder()
                        .host(endpoint.host())
                        .port(endpoint.port())
                        .database(endpoint.database())
                        .user(settings.user())
                        .password(settings.password())
                        .property("connectTimeout", "2")
                        .property("loginTimeout", "2")
                        .property("socketTimeout", "2")
                        .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new))
                        .createTables(true)
                        .build();
                return new NodeCheckpoints(CheckpointBackend.POSTGRES, saver, null);
            }
            Path sqlite = DataDirectory.of(workspace).resolve("checkpoints.db");
            Files.createDirectories(sqlite.getParent());
            BaseCheckpointSaver saver = SQLiteSaverV2.builder()
                    .databasePath(sqlite.toString())
                    .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new))
                    .createTables(true)
                    .build();
            return new NodeCheckpoints(CheckpointBackend.SQLITE, saver, sqlite);
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
