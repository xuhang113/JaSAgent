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

/**
 * 执行日志的同步门面。把异步的执行表和状态文档收成当前线程上的插入、查询和可达性检查。
 */
final class ExecutionJournal {
    /**
     * 把执行过程文档写成 JSON，也按字段顺序读回映射。
     */
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionDatabase database;
    private final ExecutionStore store;

    /**
     * 保存已经打开的库和它上面的执行存储，供后续同步调用。
     * 副作用：只持有这两个对象，不另开连接。
     *
     * @param database 执行库，可达性检查会向它借连接
     * @param store 执行行和状态文档的异步存储
     */
    private ExecutionJournal(ExecutionDatabase database, ExecutionStore store) {
        this.database = database;
        this.store = store;
    }

    /**
     * 在家目录上按给定环境打开执行库，并包上一层同步日志。
     *
     * @param home 用户数据根目录
     * @param environment 含 JDBC 等覆盖项的环境
     * @return 可立即插入和查询的日志
     */
    static ExecutionJournal open(Path home, Map<String, String> environment) {
        ExecutionDatabase database = ExecutionDatabase.open(home, environment);
        return new ExecutionJournal(database, new ExecutionStore(database));
    }

    /**
     * 插入一条执行行，并等到写入完成。
     * 副作用：执行表新增该行；失败时异常抛回调用方。
     *
     * @param row 执行编号、用户、会话、工作区、状态和 trace
     */
    void insert(ExecutionRow row) {
        store.insertExecution(row).toCompletableFuture().join();
    }

    /**
     * 把执行行的状态列改成给定值，并等到更新完成。
     * 副作用：只改状态列，不改状态文档。
     *
     * @param executionId 执行编号
     * @param status 新状态名，例如 RUNNING 或 SUSPENDED
     */
    void updateStatus(String executionId, String status) {
        store.updateStatus(executionId, status).toCompletableFuture().join();
    }

    /**
     * 按编号读取执行行。不存在时由存储抛出“执行不存在”。
     *
     * @param executionId 执行编号
     * @return 数据库中的执行行
     */
    ExecutionRow find(String executionId) {
        return store.findExecution(executionId).toCompletableFuture().join();
    }

    /**
     * 读取执行行。只有“执行不存在”这一种持久化错误当成没有记录，其他错误继续抛出。
     *
     * @param executionId 执行编号
     * @return 找到的行；库里没有这条执行时为 null
     */
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

    /**
     * 列出该用户名下的执行行。
     *
     * @param userId 用户登录名
     * @return 该用户的执行行，顺序由存储决定
     */
    List<ExecutionRow> list(String userId) {
        return store.listExecutions(userId).toCompletableFuture().join();
    }

    /**
     * 用整份 JSON 覆盖这次执行的过程文档，并等到写完。
     * 副作用：替换该执行已保存的文档，不单独改状态列。
     *
     * @param executionId 执行编号
     * @param sessionId 所属会话编号
     * @param json 过程文档的 JSON 文本
     */
    void saveDocument(String executionId, String sessionId, String json) {
        store.saveDocument(executionId, sessionId, json).toCompletableFuture().join();
    }

    /**
     * 读取执行的过程文档。
     *
     * @param executionId 执行编号
     * @return 文档 JSON；还没有文档时由存储决定返回空值
     */
    String document(String executionId) {
        return store.findDocument(executionId).toCompletableFuture().join();
    }

    /**
     * 用一条 SELECT 1 判断执行库是否还能查询。
     * @return 查询成功时为 true，SQL 失败时为 false
     */
    boolean reachable() {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * 把执行过程映射写成 JSON 字符串。写失败时抛出持久化异常，而不是返回残缺文本。
     *
     * @param body 要落库的过程字段
     * @return JSON 文本
     */
    static String writeJson(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "执行状态写入失败", null, ex);
        }
    }

    /**
     * 把过程文档读回保持字段顺序的映射。解析失败时抛出“执行状态读取失败”。
     *
     * @param json 之前写出的 JSON
     * @return 有序映射
     */
    static Map<String, Object> readJson(String json) {
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "执行状态读取失败", null, ex);
        }
    }
}
