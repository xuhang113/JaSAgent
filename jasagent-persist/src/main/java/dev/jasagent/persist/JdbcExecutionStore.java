package dev.jasagent.persist;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.persist.ExecutionRecord;
import dev.jasagent.api.persist.ExecutionStore;
import dev.jasagent.api.persist.RunSnapshot;
import dev.jasagent.api.persist.RunStore;
import org.flywaydb.core.Flyway;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

public final class JdbcExecutionStore implements ExecutionStore, RunStore, AutoCloseable {
    private final JdbcSettings settings;

    public JdbcExecutionStore(JdbcSettings settings) {
        this.settings = settings;
        if (settings.url().startsWith("jdbc:h2:file:")) {
            String path = settings.url().substring("jdbc:h2:file:".length());
            int options = path.indexOf(';');
            if (options >= 0) {
                path = path.substring(0, options);
            }
            try {
                Files.createDirectories(java.nio.file.Path.of(path).getParent());
            } catch (Exception ex) {
                throw new AgentException(ErrorCode.PERSIST, "无法创建数据库目录", null, ex);
            }
        }
        Flyway.configure()
                .dataSource(settings.url(), settings.user(), settings.password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Override
    public CompletionStage<Void> insertExecution(ExecutionRecord record) {
        return VirtualIo.supply(() -> {
            String sql = """
                    INSERT INTO execution (
                        execution_id, user_id, session_id, workspace_path, status,
                        otel_trace_id, otel_root_span_id, error_code, output_text, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            Timestamp now = Timestamp.from(Instant.now());
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.executionId());
                statement.setString(2, record.userId());
                statement.setString(3, record.sessionId());
                statement.setString(4, record.workspacePath());
                statement.setString(5, record.status());
                statement.setString(6, record.otelTraceId());
                statement.setString(7, record.otelRootSpanId());
                statement.setString(8, record.errorCode());
                statement.setString(9, record.outputText());
                statement.setTimestamp(10, now);
                statement.setTimestamp(11, now);
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw new AgentException(ErrorCode.PERSIST, "写入执行失败", record.executionId(), ex);
            }
            return null;
        });
    }

    @Override
    public CompletionStage<ExecutionRecord> findExecution(String executionId) {
        return VirtualIo.supply(() -> {
            String sql = """
                    SELECT execution_id, user_id, session_id, workspace_path, status,
                           otel_trace_id, otel_root_span_id, error_code, output_text
                    FROM execution WHERE execution_id = ?
                    """;
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, executionId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new AgentException(ErrorCode.PERSIST, "执行不存在", executionId, null);
                    }
                    return new ExecutionRecord(
                            rows.getString("execution_id"),
                            rows.getString("user_id"),
                            rows.getString("session_id"),
                            rows.getString("workspace_path"),
                            rows.getString("status"),
                            rows.getString("otel_trace_id"),
                            rows.getString("otel_root_span_id"),
                            rows.getString("error_code"),
                            rows.getString("output_text"));
                }
            } catch (AgentException ex) {
                throw ex;
            } catch (SQLException ex) {
                throw new AgentException(ErrorCode.PERSIST, "读取执行失败", executionId, ex);
            }
        });
    }

    @Override
    public CompletionStage<Void> updateExecution(String executionId, String status, String errorCode, String outputText) {
        return VirtualIo.supply(() -> {
            String sql = """
                    UPDATE execution
                    SET status = ?, error_code = ?, output_text = ?, updated_at = ?
                    WHERE execution_id = ?
                    """;
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status);
                statement.setString(2, errorCode);
                statement.setString(3, outputText);
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                statement.setString(5, executionId);
                if (statement.executeUpdate() != 1) {
                    throw new AgentException(ErrorCode.PERSIST, "执行不存在", executionId, null);
                }
            } catch (AgentException ex) {
                throw ex;
            } catch (SQLException ex) {
                throw new AgentException(ErrorCode.PERSIST, "更新执行失败", executionId, ex);
            }
            return null;
        });
    }

    @Override
    public void save(RunSnapshot snapshot) {
        String update = """
                UPDATE execution
                SET status = ?, otel_trace_id = ?, output_text = ?, updated_at = ?
                WHERE execution_id = ?
                """;
        try (Connection connection = open()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, snapshot.status());
                statement.setString(2, snapshot.traceId());
                statement.setString(3, snapshot.output());
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                statement.setString(5, snapshot.executionId());
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                String insert = """
                        INSERT INTO execution (
                            execution_id, user_id, session_id, workspace_path, status,
                            otel_trace_id, otel_root_span_id, error_code, output_text, created_at, updated_at
                        ) VALUES (?, 'user', ?, '.', ?, ?, ?, NULL, ?, ?, ?)
                        """;
                Timestamp now = Timestamp.from(Instant.now());
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setString(1, snapshot.executionId());
                    statement.setString(2, snapshot.executionId());
                    statement.setString(3, snapshot.status());
                    statement.setString(4, snapshot.traceId());
                    statement.setString(5, snapshot.traceId());
                    statement.setString(6, snapshot.output());
                    statement.setTimestamp(7, now);
                    statement.setTimestamp(8, now);
                    statement.executeUpdate();
                }
            }
            String checkpoint = """
                    INSERT INTO node_checkpoint (checkpoint_id, execution_id, node_id, step_index, state_json, committed_at)
                    VALUES (?, ?, ?, 0, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(checkpoint)) {
                statement.setString(1, java.util.UUID.randomUUID().toString());
                statement.setString(2, snapshot.executionId());
                statement.setString(3, snapshot.checkpointNode() == null ? "" : snapshot.checkpointNode());
                statement.setString(4, snapshot.output() == null ? "" : snapshot.output());
                statement.setTimestamp(5, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "保存运行快照失败", snapshot.executionId(), ex);
        }
    }

    @Override
    public RunSnapshot find(String executionId) {
        String sql = """
                SELECT e.execution_id, e.status, e.otel_trace_id, e.output_text, c.node_id
                FROM execution e
                LEFT JOIN node_checkpoint c ON c.execution_id = e.execution_id
                WHERE e.execution_id = ?
                ORDER BY c.committed_at DESC
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AgentException(ErrorCode.PERSIST, "执行不存在", executionId, null);
                }
                return new RunSnapshot(
                        rows.getString("execution_id"),
                        rows.getString("status"),
                        rows.getString("node_id"),
                        rows.getString("otel_trace_id"),
                        rows.getString("output_text"));
            }
        } catch (AgentException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "读取运行快照失败", executionId, ex);
        }
    }

    public void close() {
        if (!settings.url().startsWith("jdbc:h2:")) {
            return;
        }
        try (Connection connection = open(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (SQLException ignored) {
            // 文件库已经关闭时不需要再停一次。
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(settings.url(), settings.user(), settings.password());
    }
}
