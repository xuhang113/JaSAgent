package dev.jasagent.trace.lineage;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 把请求溯源和引用溯源写进本机 jasagent 库。
 * 口令只在打开连接时交给驱动，不放进字段、异常消息或返回值。
 * 建表脚本在同包的 {@code jasagent-lineage.sql}，只用 {@code CREATE TABLE IF NOT EXISTS}，不改已有的业务表。
 */
final class LineageDatabase {
    /**
     * 建表脚本是否已经在这次进程里执行成功。
     * 失败时保持为假，下一次调用会再执行。
     */
    private static volatile boolean schemaReady;

    /**
     * MySQL 驱动是否已经装载。装载失败不把标志改成真。
     */
    private static volatile boolean driverReady;

    /**
     * 解析后的建表语句。脚本读不到时保持为空，不缓存失败。
     */
    private static volatile List<String> statements;

    /**
     * 不保存连接和口令。每次调用自己打开连接，用完即关。
     */
    LineageDatabase() {
    }

    /**
     * 写入或替换一次用户请求，并让已有的空 traceId 引用跟上这次请求。
     * 模型调用和工具调用按这次传入的列表替换，引用行保留。
     *
     * @param password 本机 MySQL 口令，只用于登录
     * @param request 已经生成 traceId、并去掉凭据的请求
     * @return 提交后按 executionId 读回的请求
     */
    RequestTrace saveRequest(String password, RequestTrace request) {
        try (Connection connection = open(password)) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                upsertRequest(connection, request);
                replaceCalls(connection, request);
                backfillCitations(connection, request.executionId(), request.traceId());
                connection.commit();
            } catch (SQLException ex) {
                rollback(connection);
                throw new AgentException(ErrorCode.PERSIST, "用户请求溯源写入失败", request.executionId(), ex);
            } catch (RuntimeException ex) {
                rollback(connection);
                throw ex;
            }
            RequestTrace stored = findRequest(connection, request.executionId());
            if (stored == null) {
                throw new AgentException(ErrorCode.PERSIST, "用户请求溯源写入后读不到", request.executionId(), null);
            }
            return stored;
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "用户请求溯源写入失败", request.executionId(), ex);
        }
    }

    /**
     * 在一个事务里写下若干条引用。
     * 同一执行、回答、服务、文档和片段已经存在时，返回原来的那一行，并在这次带了 traceId 时补上。
     *
     * @param password 本机 MySQL 口令，只用于登录
     * @param citations 去掉凭据后的引用；traceId 可以是空串，空串时改抄请求行
     * @return 与入参顺序一致的已落库引用
     */
    List<CitationTrace> saveCitations(String password, List<CitationTrace> citations) {
        if (citations.isEmpty()) {
            return List.of();
        }
        String executionId = citations.get(0).executionId();
        try (Connection connection = open(password)) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                List<CitationTrace> stored = new ArrayList<>();
                for (CitationTrace citation : citations) {
                    stored.add(upsertCitation(connection, citation));
                }
                connection.commit();
                return List.copyOf(stored);
            } catch (SQLException ex) {
                rollback(connection);
                throw new AgentException(ErrorCode.PERSIST, "引用溯源写入失败", executionId, ex);
            } catch (RuntimeException ex) {
                rollback(connection);
                throw ex;
            }
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "引用溯源写入失败", executionId, ex);
        }
    }

    /**
     * 按 executionId 读请求、模型调用、工具调用和引用。
     * 没有请求行时请求为 null，引用仍然返回。
     *
     * @param password 本机 MySQL 口令，只用于登录
     * @param executionId 已经检查过的执行标识
     * @return 这次执行的溯源
     */
    ExecutionLineage find(String password, String executionId) {
        try (Connection connection = open(password)) {
            ensureSchema(connection);
            return new ExecutionLineage(findRequest(connection, executionId), findCitations(connection, executionId));
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "用户请求溯源读取失败", executionId, ex);
        }
    }

    /**
     * 删掉一次执行写下的请求、模型调用、工具调用和引用。
     * 只给同包测试收尾，避免测试行留在 jasagent 库里。
     *
     * @param password 本机 MySQL 口令，只用于登录
     * @param executionId 要删除的执行标识
     */
    void deleteExecution(String password, String executionId) {
        try (Connection connection = open(password)) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                deleteWhere(connection, LineageTables.MODEL_CALL, executionId);
                deleteWhere(connection, LineageTables.TOOL_CALL, executionId);
                deleteWhere(connection, LineageTables.CITATION, executionId);
                deleteWhere(connection, LineageTables.REQUEST, executionId);
                connection.commit();
            } catch (SQLException ex) {
                rollback(connection);
                throw new AgentException(ErrorCode.PERSIST, "用户请求溯源删除失败", executionId, ex);
            } catch (RuntimeException ex) {
                rollback(connection);
                throw ex;
            }
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "用户请求溯源删除失败", executionId, ex);
        }
    }

    /**
     * 用地址和账号打开一条连接，并把本次会话的字符集设成 utf8mb4。
     * 口令只出现在驱动的登录参数里，不拼进地址。
     *
     * @param password 本机 MySQL 口令
     * @return 用完必须关闭的连接
     */
    private static Connection open(String password) {
        loadDriver();
        try {
            Connection connection = DriverManager.getConnection(LineageTables.JDBC_URL, LineageTables.USER, password);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET NAMES utf8mb4");
            }
            return connection;
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "jasagent 溯源库连接失败", null, ex);
        }
    }

    /**
     * 装载 MySQL 驱动，让 {@link DriverManager} 认识 jdbc:mysql 地址。
     * 类路径上没有驱动时抛出持久化错误。
     */
    private static void loadDriver() {
        if (driverReady) {
            return;
        }
        synchronized (LineageDatabase.class) {
            if (driverReady) {
                return;
            }
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new AgentException(ErrorCode.PERSIST, "缺少 MySQL 驱动", null, ex);
            }
            driverReady = true;
        }
    }

    /**
     * 第一次写入前执行建表脚本。
     * MySQL 的建表会隐含提交，所以调用方要在开启事务之前调用本方法。
     *
     * @param connection 已经打开的 jasagent 连接
     * @throws SQLException 建表失败时抛出，由调用方收成持久化错误
     */
    private static void ensureSchema(Connection connection) throws SQLException {
        if (schemaReady) {
            return;
        }
        synchronized (LineageDatabase.class) {
            if (schemaReady) {
                return;
            }
            for (String sql : statements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            schemaReady = true;
        }
    }

    /**
     * 读取同包的建表脚本，按分号拆成语句。
     * 以 -- 开头的行是说明，不执行。脚本缺失或为空时抛出持久化错误。
     *
     * @return 去掉结尾分号的建表语句
     */
    private static List<String> statements() {
        List<String> ready = statements;
        if (ready != null) {
            return ready;
        }
        synchronized (LineageDatabase.class) {
            if (statements != null) {
                return statements;
            }
            String sql = readScript();
            List<String> parsed = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String raw : sql.split("\n", -1)) {
                String line = raw.trim();
                if (line.startsWith("--")) {
                    continue;
                }
                current.append(raw).append('\n');
                if (line.endsWith(";")) {
                    String statement = current.toString().trim();
                    if (statement.endsWith(";")) {
                        statement = statement.substring(0, statement.length() - 1).trim();
                    }
                    if (!statement.isEmpty()) {
                        parsed.add(statement);
                    }
                    current.setLength(0);
                }
            }
            if (parsed.isEmpty()) {
                throw new AgentException(ErrorCode.PERSIST, "溯源建表脚本是空的");
            }
            statements = List.copyOf(parsed);
            return statements;
        }
    }

    /**
     * 从类路径读取建表脚本的全文。
     * 读不到时抛出持久化错误，消息里不带文件内容。
     *
     * @return 脚本全文
     */
    private static String readScript() {
        try (InputStream input = LineageDatabase.class.getResourceAsStream("jasagent-lineage.sql")) {
            if (input == null) {
                throw new AgentException(ErrorCode.PERSIST, "缺少溯源建表脚本");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "缺少溯源建表脚本", null, ex);
        }
    }

    /**
     * 按 executionId 插入请求行；已经有这行时更新身份和 traceId。
     *
     * @param connection 处在事务中的连接
     * @param request 要写入的请求
     * @throws SQLException 语句失败时抛出
     */
    private static void upsertRequest(Connection connection, RequestTrace request) throws SQLException {
        String sql = "INSERT INTO " + LineageTables.REQUEST
                + " (execution_id, trace_id, tenant_id, user_id, session_id, agent_id, gateway)"
                + " VALUES (?,?,?,?,?,?,?) AS incoming"
                + " ON DUPLICATE KEY UPDATE"
                + " trace_id = incoming.trace_id,"
                + " tenant_id = incoming.tenant_id,"
                + " user_id = incoming.user_id,"
                + " session_id = incoming.session_id,"
                + " agent_id = incoming.agent_id,"
                + " gateway = incoming.gateway";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, request.executionId());
            statement.setString(2, request.traceId());
            statement.setString(3, request.tenantId());
            statement.setString(4, request.userId());
            statement.setString(5, request.sessionId());
            statement.setString(6, request.agentId());
            statement.setString(7, request.gateway());
            statement.executeUpdate();
        }
    }

    /**
     * 删掉这次执行原来的模型调用和工具调用，再写入这次带上的列表。
     * 引用不在这里删除。
     *
     * @param connection 处在事务中的连接
     * @param request 带有本次调用列表的请求
     * @throws SQLException 语句失败时抛出
     */
    private static void replaceCalls(Connection connection, RequestTrace request) throws SQLException {
        deleteWhere(connection, LineageTables.MODEL_CALL, request.executionId());
        deleteWhere(connection, LineageTables.TOOL_CALL, request.executionId());
        if (!request.modelCalls().isEmpty()) {
            String modelSql = "INSERT INTO " + LineageTables.MODEL_CALL
                    + " (execution_id, call_id, trace_id, provider, model_name) VALUES (?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(modelSql)) {
                for (ModelCallTrace call : request.modelCalls()) {
                    statement.setString(1, request.executionId());
                    statement.setString(2, call.callId());
                    statement.setString(3, request.traceId());
                    statement.setString(4, call.provider());
                    statement.setString(5, call.model());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
        if (!request.toolCalls().isEmpty()) {
            String toolSql = "INSERT INTO " + LineageTables.TOOL_CALL
                    + " (execution_id, call_id, trace_id, tool_name) VALUES (?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(toolSql)) {
                for (ToolCallTrace call : request.toolCalls()) {
                    statement.setString(1, request.executionId());
                    statement.setString(2, call.callId());
                    statement.setString(3, request.traceId());
                    statement.setString(4, call.toolName());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
    }

    /**
     * 把这次执行里还没有 traceId 的引用改成请求上的 traceId。
     * 已经写了别的 traceId 的引用保持原样。
     *
     * @param connection 处在事务中的连接
     * @param executionId 所属执行
     * @param traceId 这次请求的追踪标识
     * @throws SQLException 语句失败时抛出
     */
    private static void backfillCitations(Connection connection, String executionId, String traceId) throws SQLException {
        String sql = "UPDATE " + LineageTables.CITATION + " SET trace_id = ? WHERE execution_id = ? AND trace_id = ''";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            statement.setString(2, executionId);
            statement.executeUpdate();
        }
    }

    /**
     * 插入一条引用；自然键重复时不新开一行。
     * 入参的 traceId 为空时，改用同一连接上已经能读到的请求 traceId。
     *
     * @param connection 处在事务中的连接
     * @param citation 引用内容，citationId 由本方法生成
     * @return 库里现在的那一行
     * @throws SQLException 语句失败时抛出
     */
    private static CitationTrace upsertCitation(Connection connection, CitationTrace citation) throws SQLException {
        String traceId = citation.traceId() == null ? "" : citation.traceId();
        if (traceId.isBlank()) {
            traceId = lookupTrace(connection, citation.executionId());
        }
        String citationId = UUID.randomUUID().toString().replace("-", "");
        String key = citationKey(citation);
        String sql = "INSERT INTO " + LineageTables.CITATION
                + " (citation_id, citation_key, execution_id, trace_id, session_id, answer_id, service_name, document_id, fragment_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?) AS incoming"
                + " ON DUPLICATE KEY UPDATE"
                + " trace_id = IF(incoming.trace_id = '', " + LineageTables.CITATION + ".trace_id, incoming.trace_id),"
                + " session_id = incoming.session_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, citationId);
            statement.setString(2, key);
            statement.setString(3, citation.executionId());
            statement.setString(4, traceId);
            statement.setString(5, citation.sessionId());
            statement.setString(6, citation.answerId());
            statement.setString(7, citation.service());
            statement.setString(8, citation.documentId());
            statement.setString(9, citation.fragmentId());
            statement.executeUpdate();
        }
        CitationTrace stored = findCitationByKey(connection, key);
        if (stored == null) {
            throw new AgentException(ErrorCode.PERSIST, "引用溯源写入后读不到", citation.executionId(), null);
        }
        return stored;
    }

    /**
     * 计算引用的自然键。
     * 材料是执行、回答、服务、文档和片段，中间用换行隔开，再取 SHA-256 的小写十六进制。
     *
     * @param citation 已经去掉凭据的引用
     * @return 64 位十六进制
     */
    private static String citationKey(CitationTrace citation) {
        String raw = citation.executionId() + "\n"
                + citation.answerId() + "\n"
                + citation.service() + "\n"
                + citation.documentId() + "\n"
                + citation.fragmentId();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "SHA-256 不可用", citation.executionId(), ex);
        }
    }

    /**
     * 读取这次请求已经写下的 traceId。
     * 还没有请求行时返回空串。
     *
     * @param connection 当前连接，能看见本事务里刚写入的请求
     * @param executionId 执行标识
     * @return 追踪标识；没有时为空串
     * @throws SQLException 查询失败时抛出
     */
    private static String lookupTrace(Connection connection, String executionId) throws SQLException {
        String sql = "SELECT trace_id FROM " + LineageTables.REQUEST + " WHERE execution_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return "";
                }
                String traceId = rows.getString(1);
                return traceId == null ? "" : traceId;
            }
        }
    }

    /**
     * 按 executionId 组装请求。没有请求行时返回 null，不返回孤立的调用。
     *
     * @param connection 当前连接
     * @param executionId 执行标识
     * @return 请求；没有时为 null
     * @throws SQLException 查询失败时抛出
     */
    private static RequestTrace findRequest(Connection connection, String executionId) throws SQLException {
        String sql = "SELECT trace_id, tenant_id, user_id, session_id, agent_id, gateway FROM "
                + LineageTables.REQUEST + " WHERE execution_id = ?";
        String traceId;
        String tenantId;
        String userId;
        String sessionId;
        String agentId;
        String gateway;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                traceId = rows.getString("trace_id");
                tenantId = rows.getString("tenant_id");
                userId = rows.getString("user_id");
                sessionId = rows.getString("session_id");
                agentId = rows.getString("agent_id");
                gateway = rows.getString("gateway");
            }
        }
        return new RequestTrace(
                traceId,
                executionId,
                tenantId,
                userId,
                sessionId,
                agentId,
                gateway,
                findModelCalls(connection, executionId),
                findToolCalls(connection, executionId));
    }

    /**
     * 读取这次执行的模型调用，按调用标识排序。
     *
     * @param connection 当前连接
     * @param executionId 执行标识
     * @return 模型调用；没有时为空列表
     * @throws SQLException 查询失败时抛出
     */
    private static List<ModelCallTrace> findModelCalls(Connection connection, String executionId) throws SQLException {
        String sql = "SELECT call_id, provider, model_name, trace_id FROM " + LineageTables.MODEL_CALL
                + " WHERE execution_id = ? ORDER BY call_id";
        List<ModelCallTrace> calls = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    calls.add(new ModelCallTrace(
                            rows.getString("call_id"),
                            rows.getString("provider"),
                            rows.getString("model_name"),
                            rows.getString("trace_id"),
                            executionId));
                }
            }
        }
        return calls;
    }

    /**
     * 读取这次执行的工具调用，按调用标识排序。
     *
     * @param connection 当前连接
     * @param executionId 执行标识
     * @return 工具调用；没有时为空列表
     * @throws SQLException 查询失败时抛出
     */
    private static List<ToolCallTrace> findToolCalls(Connection connection, String executionId) throws SQLException {
        String sql = "SELECT call_id, tool_name, trace_id FROM " + LineageTables.TOOL_CALL
                + " WHERE execution_id = ? ORDER BY call_id";
        List<ToolCallTrace> calls = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    calls.add(new ToolCallTrace(
                            rows.getString("call_id"),
                            rows.getString("tool_name"),
                            rows.getString("trace_id"),
                            executionId));
                }
            }
        }
        return calls;
    }

    /**
     * 读取这次执行的引用，先按写入时间，再按引用标识。
     *
     * @param connection 当前连接
     * @param executionId 执行标识
     * @return 引用；没有时为空列表
     * @throws SQLException 查询失败时抛出
     */
    private static List<CitationTrace> findCitations(Connection connection, String executionId) throws SQLException {
        String sql = "SELECT citation_id, trace_id, session_id, answer_id, service_name, document_id, fragment_id FROM "
                + LineageTables.CITATION + " WHERE execution_id = ? ORDER BY created_at, citation_id";
        List<CitationTrace> citations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    citations.add(new CitationTrace(
                            rows.getString("citation_id"),
                            executionId,
                            rows.getString("trace_id"),
                            rows.getString("session_id"),
                            rows.getString("answer_id"),
                            rows.getString("service_name"),
                            rows.getString("document_id"),
                            rows.getString("fragment_id")));
                }
            }
        }
        return citations;
    }

    /**
     * 按自然键读一条引用。
     *
     * @param connection 当前连接
     * @param key 引用自然键
     * @return 引用；没有时为 null
     * @throws SQLException 查询失败时抛出
     */
    private static CitationTrace findCitationByKey(Connection connection, String key) throws SQLException {
        String sql = "SELECT citation_id, execution_id, trace_id, session_id, answer_id, service_name, document_id, fragment_id FROM "
                + LineageTables.CITATION + " WHERE citation_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new CitationTrace(
                        rows.getString("citation_id"),
                        rows.getString("execution_id"),
                        rows.getString("trace_id"),
                        rows.getString("session_id"),
                        rows.getString("answer_id"),
                        rows.getString("service_name"),
                        rows.getString("document_id"),
                        rows.getString("fragment_id"));
            }
        }
    }

    /**
     * 按 executionId 删除一张溯源表里的行。表名只来自 {@link LineageTables}。
     *
     * @param connection 处在事务中的连接
     * @param table 要删除的表
     * @param executionId 执行标识
     * @throws SQLException 语句失败时抛出
     */
    private static void deleteWhere(Connection connection, String table, String executionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE execution_id = ?")) {
            statement.setString(1, executionId);
            statement.executeUpdate();
        }
    }

    /**
     * 回滚当前事务。
     * 回滚自己失败时不再向外抛，避免盖住原来的写入错误，也不把第二条异常的文本发出去。
     *
     * @param connection 要回滚的连接
     */
    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留调用方已经抓住的那条失败。
        }
    }
}
