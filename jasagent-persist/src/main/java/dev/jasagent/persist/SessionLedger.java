package dev.jasagent.persist;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.runtime.GatewayHeaders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话归属和会话文档的 MySQL 账本。所有者以表里的租户和用户为准，内存里的文档只是更新时间对得上时的缓存。
 * 网关用户与所有者不一致时抛出明确错误，并且不写库。
 */
public final class SessionLedger {
    private final ExecutionDatabase database;
    /**
     * 文档缓存。键是租户和会话。命中前仍会向库核对所有者和更新时间。
     */
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 记下已经建过表的执行库。
     *
     * @param database 连到 MySQL 的执行库
     */
    private SessionLedger(ExecutionDatabase database) {
        this.database = database;
    }

    /**
     * 在执行库上补齐表并返回账本。连接失败时抛出持久化异常，消息不含口令。
     *
     * @param database 打开的执行库
     * @return 可用的会话账本
     */
    public static SessionLedger open(ExecutionDatabase database) {
        try (Connection connection = database.connect()) {
            MysqlSchemas.ensure(connection);
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "会话库无法打开", null, null);
        }
        return new SessionLedger(database);
    }

    /**
     * 记下该租户下这个用户当前的工作区。之后提交执行从这里读，不从登录会话读。
     * 副作用：覆盖同一租户和用户的工作区路径。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     * @param workspace 已经通过允许列表的绝对路径
     */
    public void saveWorkspace(String tenantId, String userId, String workspace) {
        String sql = """
                INSERT INTO agent_workspace (tenant_id, user_id, workspace_path) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE workspace_path = VALUES(workspace_path)
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, userId);
            statement.setString(3, workspace);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "工作区无法写入", null, null);
        }
    }

    /**
     * 读取该租户下这个用户保存的工作区。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     * @return 工作区路径；还没选过时是空串
     */
    public String workspace(String tenantId, String userId) {
        String sql = "SELECT workspace_path FROM agent_workspace WHERE tenant_id = ? AND user_id = ?";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return "";
                }
                String value = rows.getString(1);
                return value == null ? "" : value;
            }
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "工作区无法读取", null, null);
        }
    }

    /**
     * 插入或覆盖一场会话。调用前应已经核对过所有者。文档以这次写入的 JSON 为准，并刷新缓存。
     * 副作用：写 agent_session。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @param userId 所有者用户 id
     * @param title 标题
     * @param model 模型 id
     * @param provider 供应商
     * @param titleCustom 标题是否手改过
     * @param createdAt 创建时间文本，已有行时保留库里的创建时间
     * @param updatedAt 这次写入的时间
     * @param document 会话 JSON
     */
    public void save(String tenantId, String sessionId, String userId, String title, String model, String provider,
                     boolean titleCustom, String createdAt, String updatedAt, String document) {
        Row existing = findRow(tenantId, sessionId);
        if (existing != null && !existing.userId.equals(userId)) {
            throw mismatch();
        }
        String created = existing == null ? createdAt : existing.createdAt;
        String sql = """
                INSERT INTO agent_session (
                    tenant_id, session_id, user_id, title, model_name, model_provider, title_custom, document, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    title = VALUES(title),
                    model_name = VALUES(model_name),
                    model_provider = VALUES(model_provider),
                    title_custom = VALUES(title_custom),
                    document = VALUES(document),
                    updated_at = VALUES(updated_at)
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sessionId);
            statement.setString(3, userId);
            statement.setString(4, title == null ? "" : title);
            statement.setString(5, model == null ? "" : model);
            statement.setString(6, provider == null ? "" : provider);
            statement.setBoolean(7, titleCustom);
            statement.setString(8, document == null ? "" : document);
            statement.setString(9, created);
            statement.setString(10, updatedAt);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "会话无法写入", null, null);
        }
        cache.put(key(tenantId, sessionId), new Cached(userId, updatedAt, document == null ? "" : document, created));
    }

    /**
     * 按租户和会话读取文档。先向库核对所有者和更新时间，一致时才用缓存里的 JSON。
     *
     * @param tenantId 租户 id
     * @param userId 请求者用户 id，必须是所有者
     * @param sessionId 会话编号
     * @return 会话 JSON；不存在或不属于该用户时抛出认证异常，且不改库
     */
    public String load(String tenantId, String userId, String sessionId) {
        Row row = requireOwned(tenantId, userId, sessionId);
        Cached cached = cache.get(key(tenantId, sessionId));
        if (cached != null && cached.userId.equals(row.userId) && cached.updatedAt.equals(row.updatedAt)) {
            return cached.document;
        }
        String sql = "SELECT document FROM agent_session WHERE tenant_id = ? AND session_id = ?";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw missing();
                }
                String document = rows.getString(1);
                String body = document == null ? "" : document;
                cache.put(key(tenantId, sessionId), new Cached(row.userId, row.updatedAt, body, row.createdAt));
                return body;
            }
        } catch (AgentException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "会话无法读取", null, null);
        }
    }

    /**
     * 只核对所有者，不返回文档，也不写库。不一致时抛出明确错误。
     *
     * @param tenantId 租户 id
     * @param userId 网关用户 id
     * @param sessionId 会话编号
     */
    public void assertOwner(String tenantId, String userId, String sessionId) {
        requireOwned(tenantId, userId, sessionId);
    }

    /**
     * 列出该租户下这个用户的会话，按创建时间排序。
     *
     * @param tenantId 租户 id
     * @param userId 所有者用户 id
     * @return 会话摘要，没有时是空列表
     */
    public List<Row> list(String tenantId, String userId) {
        String sql = """
                SELECT tenant_id, session_id, user_id, title, model_name, model_provider, title_custom, created_at, updated_at
                FROM agent_session WHERE tenant_id = ? AND user_id = ? ORDER BY created_at
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, userId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Row> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readIdentity(rows));
                }
                return found;
            }
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "会话无法列出", null, null);
        }
    }

    /**
     * 删除属于该用户的会话。别人的会话不会删，缓存里的副本也会去掉。
     * 副作用：删除一行。
     *
     * @param tenantId 租户 id
     * @param userId 请求者，必须是所有者
     * @param sessionId 会话编号
     */
    public void delete(String tenantId, String userId, String sessionId) {
        requireOwned(tenantId, userId, sessionId);
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM agent_session WHERE tenant_id = ? AND session_id = ? AND user_id = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, sessionId);
            statement.setString(3, userId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "会话无法删除", null, null);
        }
        cache.remove(key(tenantId, sessionId));
    }

    /**
     * 读取会话身份。不存在时返回 null。这个方法不检查调用者是谁，供恢复和比对使用。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @return 身份行；没有这场会话时为 null
     */
    public Row find(String tenantId, String sessionId) {
        return findRow(tenantId, sessionId);
    }

    /**
     * 按会话编号查出全部租户下的身份行。网关在转发前用它判断当前用户是不是所有者。
     * 不另建会话表。没有这场会话时是空列表，调用方可以继续转给当前用户自己的 agent。
     *
     * @param sessionId 会话编号
     * @return 身份行；编号空白或没有行时是空列表
     */
    public List<Row> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT tenant_id, session_id, user_id, title, model_name, model_provider, title_custom, created_at, updated_at
                FROM agent_session WHERE session_id = ?
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Row> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readIdentity(rows));
                }
                return found;
            }
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "会话无法读取", null, null);
        }
    }

    /**
     * 把一个用户在旧租户下的聊天会话、工作区和工作执行改到他自己的租户。
     * 只改归属列，不复制一份账本。旧租户和新租户相同、或任一键空白时什么都不做。
     * 副作用：更新已有行，并清掉内存缓存。
     *
     * @param fromTenant 迁出的租户，历史数据是 local
     * @param userId 所有者登录名
     * @param toTenant 该用户自己的租户
     */
    public void moveUser(String fromTenant, String userId, String toTenant) {
        if (fromTenant == null || userId == null || toTenant == null) {
            return;
        }
        if (fromTenant.isBlank() || userId.isBlank() || toTenant.isBlank() || fromTenant.equals(toTenant)) {
            return;
        }
        try (Connection connection = database.connect()) {
            moveRows(connection, "UPDATE agent_session SET tenant_id = ? WHERE tenant_id = ? AND user_id = ?", toTenant, fromTenant, userId);
            moveRows(connection, "UPDATE agent_workspace SET tenant_id = ? WHERE tenant_id = ? AND user_id = ?", toTenant, fromTenant, userId);
            moveRows(connection, "UPDATE execution SET tenant_id = ? WHERE tenant_id = ? AND user_id = ?", toTenant, fromTenant, userId);
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "会话归属无法对齐", null, null);
        }
        cache.clear();
    }

    /**
     * 执行一条只改租户的更新。表或列还不存在时跳过，其它失败继续抛出，消息由调用方改写。
     *
     * @param connection 当前连接
     * @param sql 带三个占位符的更新：新租户、旧租户、用户
     * @param toTenant 新租户
     * @param fromTenant 旧租户
     * @param userId 用户
     * @throws SQLException 不是缺表或缺列的失败
     */
    private static void moveRows(Connection connection, String sql, String toTenant, String fromTenant, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toTenant);
            statement.setString(2, fromTenant);
            statement.setString(3, userId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1054 || ex.getErrorCode() == 1146) {
                return;
            }
            throw ex;
        }
    }

    /**
     * 读出身份并确认请求者就是所有者。不存在时按无权读取拒绝，所有者不同则返回明确的不一致错误。两种情况都不写库。
     *
     * @param tenantId 租户 id
     * @param userId 请求者用户 id
     * @param sessionId 会话编号
     * @return 库里的身份行
     */
    private Row requireOwned(String tenantId, String userId, String sessionId) {
        Row row = findRow(tenantId, sessionId);
        if (row == null) {
            throw missing();
        }
        if (!row.userId.equals(userId)) {
            throw mismatch();
        }
        return row;
    }

    /**
     * 按主键读取身份列，不读取大文档。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @return 身份；没有行时为 null
     */
    private Row findRow(String tenantId, String sessionId) {
        String sql = """
                SELECT tenant_id, session_id, user_id, title, model_name, model_provider, title_custom, created_at, updated_at
                FROM agent_session WHERE tenant_id = ? AND session_id = ?
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return readIdentity(rows);
            }
        } catch (SQLException ex) {
            throw new AgentException(ErrorCode.PERSIST, "会话无法读取", null, null);
        }
    }

    /**
     * 从当前结果行读出身份列。列顺序必须与查询一致。
     *
     * @param rows 已经定位到一行的结果
     * @return 身份
     * @throws SQLException 驱动读列失败
     */
    private static Row readIdentity(ResultSet rows) throws SQLException {
        return new Row(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5),
                rows.getString(6),
                rows.getBoolean(7),
                rows.getString(8),
                rows.getString(9));
    }

    /**
     * 缓存键。租户和会话用换行隔开，避免简单拼接时撞车。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @return 缓存键
     */
    private static String key(String tenantId, String sessionId) {
        return tenantId + "\n" + sessionId;
    }

    /**
     * 会话不存在或调用者无权把它当成自己的会话时使用的异常。
     *
     * @return 认证异常
     */
    private static AgentException missing() {
        return new AgentException(ErrorCode.AUTH, "不能读取其他用户的会话");
    }

    /**
     * 库里已经有这场会话，但网关用户不是所有者。
     *
     * @return 带明确说明的认证异常
     */
    private static AgentException mismatch() {
        return new AgentException(ErrorCode.AUTH, GatewayHeaders.OWNER_MISMATCH);
    }

    /**
     * 一场会话的身份，不含大文档。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @param userId 所有者
     * @param title 标题
     * @param model 模型 id
     * @param provider 供应商
     * @param titleCustom 标题是否手改过
     * @param createdAt 创建时间
     * @param updatedAt 更新时间，用来判断缓存是否过期
     */
    public record Row(
            String tenantId,
            String sessionId,
            String userId,
            String title,
            String model,
            String provider,
            boolean titleCustom,
            String createdAt,
            String updatedAt) {
    }

    /**
     * 缓存里的文档副本。更新时间或所有者与库不一致时丢弃。
     *
     * @param userId 写入缓存时的所有者
     * @param updatedAt 写入缓存时的更新时间
     * @param document 会话 JSON
     * @param createdAt 创建时间
     */
    private record Cached(String userId, String updatedAt, String document, String createdAt) {
    }
}
