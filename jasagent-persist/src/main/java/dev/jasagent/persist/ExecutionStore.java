package dev.jasagent.persist;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** 对执行、模型调用、任务和界面文档做异步读写。SQL 失败一律收成带执行编号的持久化异常。 */
public final class ExecutionStore {
    private final ExecutionDatabase database;

    /**
     * 绑定已经迁移好的执行库。
     *
     * @param database 打开的执行库
     */
    public ExecutionStore(ExecutionDatabase database) {
        this.database = database;
    }

    /**
     * 向 execution 表插入一行，记下用户、会话、工作区、状态和两条 OpenTelemetry 编号。数据库拒绝写入时阶段以持久化异常失败。
     *
     * @param row 要写入的执行
     * @return 插入成功后结束的阶段，没有结果值
     */
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

    /**
     * 按执行编号读回一整行。没有这一行时阶段以持久化异常失败。
     *
     * @param executionId 执行编号
     * @return 完成后得到该行
     */
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
                    return readExecution(rows);
                }
            } catch (SQLException ex) {
                throw persist(executionId, "执行读取失败", ex);
            }
        });
    }

    /**
     * 向 llm_call 表插入一次模型调用，记下供应商、模型、耗时和三段 token 数。
     *
     * @param row 要写入的调用
     * @return 插入成功后结束的阶段，没有结果值
     */
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

    /**
     * 按调用编号读回模型调用。没有这一行时阶段以持久化异常失败。
     *
     * @param callId 模型调用编号
     * @return 完成后得到该行
     */
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

    /**
     * 把执行状态改成给定值。没有正好更新一行时视为执行不存在。
     *
     * @param executionId 执行编号
     * @param status 新状态
     * @return 更新成功后结束的阶段，没有结果值
     */
    public CompletionStage<Void> updateStatus(String executionId, String status) {
        return VirtualIo.supply(() -> {
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE execution SET status = ? WHERE execution_id = ?")) {
                statement.setString(1, status);
                statement.setString(2, executionId);
                if (statement.executeUpdate() != 1) {
                    throw persist(executionId, "执行不存在", null);
                }
                return null;
            } catch (SQLException ex) {
                throw persist(executionId, "执行状态写入失败", ex);
            }
        });
    }

    /**
     * 列出某个用户的全部执行，按执行编号排序。
     *
     * @param userId 用户编号
     * @return 完成后得到该用户的执行列表，没有记录时为空列表
     */
    public CompletionStage<List<ExecutionRow>> listExecutions(String userId) {
        return VirtualIo.supply(() -> {
            String sql = """
                    SELECT execution_id, user_id, session_id, workspace_path, status, otel_trace_id, otel_root_span_id
                    FROM execution WHERE user_id = ? ORDER BY execution_id
                    """;
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ExecutionRow> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(readExecution(rows));
                    }
                    return found;
                }
            } catch (SQLException ex) {
                throw persist(null, "执行读取失败", ex);
            }
        });
    }

    /**
     * 用固定消息编号覆盖保存界面文档。空白会话编号改用执行编号，空正文存成空串。删除旧消息和插入新消息放在同一个事务里。
     *
     * @param executionId 执行编号，同时参与拼消息编号
     * @param sessionId 会话编号，空白时改用执行编号
     * @param body 文档正文
     * @return 事务提交后结束的阶段，没有结果值
     */
    public CompletionStage<Void> saveDocument(String executionId, String sessionId, String body) {
        return VirtualIo.supply(() -> {
            String messageId = "view-" + executionId;
            try (Connection connection = database.connect()) {
                connection.setAutoCommit(false);
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM message WHERE message_id = ?")) {
                    delete.setString(1, messageId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO message (message_id, execution_id, session_id, role, body) VALUES (?, ?, ?, ?, ?)")) {
                    insert.setString(1, messageId);
                    insert.setString(2, executionId);
                    insert.setString(3, sessionId == null || sessionId.isBlank() ? executionId : sessionId);
                    insert.setString(4, "view");
                    insert.setString(5, body == null ? "" : body);
                    insert.executeUpdate();
                }
                connection.commit();
                return null;
            } catch (SQLException ex) {
                throw persist(executionId, "执行状态写入失败", ex);
            }
        });
    }

    /**
     * 读取这条执行对应的界面文档正文。还没保存过时结果为空，不算失败。
     *
     * @param executionId 执行编号
     * @return 完成后得到正文；没有对应消息时为空
     */
    public CompletionStage<String> findDocument(String executionId) {
        return VirtualIo.supply(() -> {
            try (Connection connection = database.connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT body FROM message WHERE message_id = ?")) {
                statement.setString(1, "view-" + executionId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    return rows.getString(1);
                }
            } catch (SQLException ex) {
                throw persist(executionId, "执行状态读取失败", ex);
            }
        });
    }

    /**
     * 插入一条任务，错误码留空。
     *
     * @param taskId 任务编号
     * @param executionId 所属执行编号
     * @param status 初始状态
     * @return 插入成功后结束的阶段，没有结果值
     */
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

    /**
     * 把任务标成失败并写下错误码。没有正好更新一行时视为任务不存在。
     *
     * @param taskId 任务编号
     * @param errorCode 失败原因码
     * @return 更新成功后结束的阶段，没有结果值
     */
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

    /**
     * 读取任务当前状态。任务不存在时阶段失败。
     *
     * @param taskId 任务编号
     * @return 完成后得到 status 列
     */
    public CompletionStage<String> taskStatus(String taskId) {
        return taskColumn(taskId, "status");
    }

    /**
     * 读取任务上记录的错误码。还没失败时结果为空。
     *
     * @param taskId 任务编号
     * @return 完成后得到 error_code 列
     */
    public CompletionStage<String> taskErrorCode(String taskId) {
        return taskColumn(taskId, "error_code");
    }

    /**
     * 按任务编号读取指定列。任务不存在时抛出持久化异常。
     *
     * @param taskId 任务编号
     * @param column 要读取的列名，调用方只传入 status 或 error_code
     * @return 完成后得到该列文本
     */
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

    /**
     * 按查询列顺序组装执行行：编号、用户、会话、工作区、状态、追踪编号、根 span 编号。
     *
     * @param rows 已经定位到当前行的结果集
     * @return 这一行对应的执行记录
     * @throws SQLException 读列失败时抛出
     */
    private static ExecutionRow readExecution(ResultSet rows) throws SQLException {
        return new ExecutionRow(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5),
                rows.getString(6),
                rows.getString(7));
    }

    /**
     * 把数据库失败收成持久化异常，并带上能定位的执行编号。
     *
     * @param executionId 相关执行编号，没有时为空
     * @param message 给调用方看的失败说明
     * @param cause 原始异常，没有时为空
     * @return 持久化异常
     */
    private static AgentException persist(String executionId, String message, Throwable cause) {
        return new AgentException(ErrorCode.PERSIST, message, executionId, cause);
    }
}
