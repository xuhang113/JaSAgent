package dev.jasagent.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.persist.ExecutionDatabase;
import dev.jasagent.persist.ExecutionRow;
import dev.jasagent.persist.ExecutionStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExecutionJournal {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionDatabase database;
    private final ExecutionStore store;

    private ExecutionJournal(ExecutionDatabase database, ExecutionStore store) {
        this.database = database;
        this.store = store;
    }

    static ExecutionJournal open(Path home, Map<String, String> environment) {
        ExecutionDatabase database = ExecutionDatabase.open(home, environment);
        return new ExecutionJournal(database, new ExecutionStore(database));
    }

    void insert(ExecutionRow row) {
        store.insertExecution(row).toCompletableFuture().join();
    }

    void updateStatus(String executionId, String status) {
        store.updateStatus(executionId, status).toCompletableFuture().join();
    }

    ExecutionRow find(String executionId) {
        return store.findExecution(executionId).toCompletableFuture().join();
    }

    ExecutionRow findIfPresent(String executionId) {
        try {
            return find(executionId);
        } catch (AgentException ex) {
            if (ex.code() == ErrorCode.PERSIST && "执行不存在".equals(ex.getMessage())) {
                return null;
            }
            throw ex;
        }
    }

    List<ExecutionRow> list(String userId) {
        return store.listExecutions(userId).toCompletableFuture().join();
    }

    void saveDocument(String executionId, String sessionId, String json) {
        store.saveDocument(executionId, sessionId, json).toCompletableFuture().join();
    }

    String document(String executionId) {
        return store.findDocument(executionId).toCompletableFuture().join();
    }

    boolean reachable() {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    static String writeJson(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "执行状态写入失败", null, ex);
        }
    }

    static Map<String, Object> readJson(String json) {
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "执行状态读取失败", null, ex);
        }
    }
}
