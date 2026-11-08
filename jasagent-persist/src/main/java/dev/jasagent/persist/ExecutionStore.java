package dev.jasagent.persist;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletionStage;

public final class ExecutionStore {
    private final ExecutionDatabase database;

    public ExecutionStore(ExecutionDatabase database) {
        this.database = database;
    }

    public CompletionStage<Void> insertExecution(ExecutionRow row) {
        return VirtualIo.supply(() -> {
            String sql = """
                    INSERT INTO execution (
                        execution_id, user_id, session_id, workspace_path, status, otel_trace_id, otel_root_span_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, row.executionId());
                statement.setString(2, row.userId());
                statement.setString(3, row.sessionId());
                statement.setString(4, row.workspacePath());
                statement.setString(5, row.status());
                statement.setString(6, row.otelTraceId());
                statement.setString(7, row.otelRootSpanId());
                statement.executeUpdate();
                return null;
            } catch (SQLException ex) {
                throw persist(row.executionId(), "执行写入失败", ex);
            }
        });
    }

    public CompletionStage<ExecutionRow> findExecution(String executionId) {
        return VirtualIo.supply(() -> {
            String sql = """
                    SELECT execution_id, user_id, session_id, workspace_path, status, otel_trace_id, otel_root_span_id
                    FROM execution WHERE execution_id = ?
                    """;
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, executionId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw persist(executionId, "执行不存在", null);
                    }
                    return new ExecutionRow(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6),
                            rows.getString(7));
                }
            } catch (SQLException ex) {
                throw persist(executionId, "执行读取失败", ex);
            }
        });
    }

    public CompletionStage<Void> insertLlmCall(LlmCallRow row) {
        return VirtualIo.supply(() -> {
            String sql = """
                    INSERT INTO llm_call (
                        call_id, execution_id, provider, model_name, latency_ms, prompt_tokens, completion_tokens, total_tokens
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, row.callId());
                statement.setString(2, row.executionId());
                statement.setString(3, row.provider());
                statement.setString(4, row.modelName());
                statement.setLong(5, row.latencyMs());
                statement.setInt(6, row.promptTokens());
                statement.setInt(7, row.completionTokens());
                statement.setInt(8, row.totalTokens());
                statement.executeUpdate();
                return null;
            } catch (SQLException ex) {
                throw persist(row.executionId(), "模型调用写入失败", ex);
            }
        });
    }

    public CompletionStage<LlmCallRow> findLlmCall(String callId) {
        return VirtualIo.supply(() -> {
            String sql = """
                    SELECT call_id, execution_id, provider, model_name, latency_ms, prompt_tokens, completion_tokens, total_tokens
                    FROM llm_call WHERE call_id = ?
                    """;
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, callId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw persist(null, "模型调用不存在", null);
                    }
                    return new LlmCallRow(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getLong(5),
                            rows.getInt(6),
                            rows.getInt(7),
                            rows.getInt(8));
                }
            } catch (SQLException ex) {
                throw persist(null, "模型调用读取失败", ex);
            }
        });
    }

    public CompletionStage<Void> insertTask(String taskId, String executionId, String status) {
        return VirtualIo.supply(() -> {
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO task (task_id, execution_id, status, error_code) VALUES (?, ?, ?, NULL)")) {
                statement.setString(1, taskId);
                statement.setString(2, executionId);
                statement.setString(3, status);
                statement.executeUpdate();
                return null;
            } catch (SQLException ex) {
                throw persist(executionId, "任务写入失败", ex);
            }
        });
    }

    public CompletionStage<Void> markTaskFailed(String taskId, String errorCode) {
        return VirtualIo.supply(() -> {
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE task SET status = 'FAILED', error_code = ? WHERE task_id = ?")) {
                statement.setString(1, errorCode);
                statement.setString(2, taskId);
                if (statement.executeUpdate() != 1) {
                    throw persist(null, "任务不存在", null);
                }
                return null;
            } catch (SQLException ex) {
                throw persist(null, "任务状态写入失败", ex);
            }
        });
    }

    public CompletionStage<String> taskStatus(String taskId) {
        return taskColumn(taskId, "status");
    }

    public CompletionStage<String> taskErrorCode(String taskId) {
        return taskColumn(taskId, "error_code");
    }

    private CompletionStage<String> taskColumn(String taskId, String column) {
        return VirtualIo.supply(() -> {
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT " + column + " FROM task WHERE task_id = ?")) {
                statement.setString(1, taskId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw persist(null, "任务不存在", null);
                    }
                    return rows.getString(1);
                }
            } catch (SQLException ex) {
                throw persist(null, "任务读取失败", ex);
            }
        });
    }

    private static AgentException persist(String executionId, String message, Throwable cause) {
        return new AgentException(ErrorCode.PERSIST, message, executionId, cause);
    }
}
