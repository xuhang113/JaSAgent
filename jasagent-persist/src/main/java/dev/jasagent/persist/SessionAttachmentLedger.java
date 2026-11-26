package dev.jasagent.persist;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话附件的 MySQL 账本。元数据和正文写在已有 jasagent 库的 {@code session_attachment}，不另建库，也不放进进程内缓存。
 * 每一行同时记下租户、会话和所有者。读取、列出和删除都必须带上这三列，不能只凭附件编号。
 * 对象路径由服务端生成，以租户和会话为前缀；用户文件名只作展示，不进入路径。
 */
public final class SessionAttachmentLedger {
    /**
     * 租户、会话、所有者和附件编号的单个路径分量。不允许空、点段或超过列宽。
     */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    /**
     * 会话编号和附件编号的列宽。超长的标识不写入。
     */
    private static final int ID_LIMIT = 64;
    /**
     * 展示名最长字符数。更长的部分丢掉，避免撑破列，也不进入对象路径。
     */
    private static final int NAME_LIMIT = 120;

    private final ExecutionDatabase database;

    /**
     * 记下已经连上 jasagent 的执行库。正文不在这里缓存。
     *
     * @param database 打开的执行库
     */
    private SessionAttachmentLedger(ExecutionDatabase database) {
        this.database = database;
    }

    /**
     * 在执行库上补齐表并返回附件账本。连接失败时抛出持久化异常，消息不含口令。
     *
     * @param database 打开的执行库
     * @return 可用的附件账本
     */
    public static SessionAttachmentLedger open(ExecutionDatabase database) {
        try (Connection connection = database.connect()) {
            MysqlSchemas.ensure(connection);
        } catch (SQLException ex) {
            throw persist("附件库无法打开");
        }
        return new SessionAttachmentLedger(database);
    }

    /**
     * 把展示名收成单个文件名。去掉目录、引号和控制字符。空、{@code .} 和 {@code ..} 变成空串。
     * 结果只用于展示，不能拿去拼对象路径。
     *
     * @param raw 客户端给出的文件名，可以带目录
     * @return 展示名；不能用时是空串
     */
    public static String displayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length() && out.length() < NAME_LIMIT; i++) {
            char current = name.charAt(i);
            if (current < 0x20 || current == '"' || current == '\\' || current == '/' || current == ';') {
                continue;
            }
            out.append(current);
        }
        String cleaned = out.toString().trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned) || cleaned.contains("..")) {
            return "";
        }
        return cleaned;
    }

    /**
     * 写入一条附件。附件编号和对象路径都在这里生成，调用方给不了存储路径。
     * 类型和大小由网关先拒绝；这里只拒绝不合法的标识，避免把用户文件名写进路径。
     * 副作用：在同一条语句里插入元数据和正文，失败时整行不留。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @param userId 所有者登录名
     * @param mediaType 网关已经探测过的类型
     * @param rawName 客户端文件名，只作展示
     * @param content 附件原始字节
     * @return 已落库的附件；标识不合法时抛出持久化异常，且不写库
     */
    public Stored insert(String tenantId, String sessionId, String userId, String mediaType, String rawName, byte[] content) {
        String tenant = segment(tenantId, 128);
        String session = segment(sessionId, ID_LIMIT);
        String user = segment(userId, 255);
        String attachmentId = segment(UUID.randomUUID().toString(), ID_LIMIT);
        String objectPath = objectPath(tenant, session, attachmentId);
        String name = displayName(rawName);
        String createdAt = Instant.now().toString();
        byte[] body = content == null ? new byte[0] : content.clone();
        String sql = """
                INSERT INTO session_attachment (
                    tenant_id, session_id, user_id, attachment_id, object_path, media_type, byte_size, display_name, content, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tenant);
                statement.setString(2, session);
                statement.setString(3, user);
                statement.setString(4, attachmentId);
                statement.setString(5, objectPath);
                statement.setString(6, mediaType == null ? "" : mediaType);
                statement.setLong(7, body.length);
                statement.setString(8, name);
                statement.setBytes(9, body);
                statement.setString(10, createdAt);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw persist("附件无法写入");
            }
        } catch (AgentException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw persist("附件无法写入");
        }
        return new Stored(attachmentId, session, tenant, user, mediaType, body.length, name, objectPath, createdAt, body);
    }

    /**
     * 按租户、会话、所有者和附件编号读取正文。四列缺一不可，编号对不上时为空，不写库。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @param userId 所有者登录名
     * @param attachmentId 附件编号
     * @return 附件；不属于这三列时为空
     */
    public Optional<Stored> find(String tenantId, String sessionId, String userId, String attachmentId) {
        if (!valid(tenantId, 128) || !valid(sessionId, ID_LIMIT) || !valid(userId, 255) || !valid(attachmentId, ID_LIMIT)) {
            return Optional.empty();
        }
        String sql = """
                SELECT attachment_id, session_id, tenant_id, user_id, media_type, byte_size, display_name, object_path, created_at, content
                FROM session_attachment
                WHERE tenant_id = ? AND session_id = ? AND user_id = ? AND attachment_id = ?
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sessionId);
            statement.setString(3, userId);
            statement.setString(4, attachmentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(readStored(rows));
            }
        } catch (SQLException ex) {
            throw persist("附件无法读取");
        }
    }

    /**
     * 列出这场会话里这个所有者的附件，不含正文。条件同样带租户、会话和所有者。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @param userId 所有者登录名
     * @return 摘要；标识不合法或没有行时是空列表
     */
    public List<Summary> list(String tenantId, String sessionId, String userId) {
        if (!valid(tenantId, 128) || !valid(sessionId, ID_LIMIT) || !valid(userId, 255)) {
            return List.of();
        }
        String sql = """
                SELECT attachment_id, session_id, media_type, byte_size, display_name, object_path, created_at
                FROM session_attachment
                WHERE tenant_id = ? AND session_id = ? AND user_id = ?
                ORDER BY created_at, attachment_id
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sessionId);
            statement.setString(3, userId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Summary> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readSummary(rows));
                }
                return found;
            }
        } catch (SQLException ex) {
            throw persist("附件无法列出");
        }
    }

    /**
     * 删除这个所有者在这场会话里的一条附件。别人的行不受影响。
     * 副作用：删掉一行。没有匹配行时返回假，不报错。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @param userId 所有者登录名
     * @param attachmentId 附件编号
     * @return 确实删掉一行时为真
     */
    public boolean delete(String tenantId, String sessionId, String userId, String attachmentId) {
        if (!valid(tenantId, 128) || !valid(sessionId, ID_LIMIT) || !valid(userId, 255) || !valid(attachmentId, ID_LIMIT)) {
            return false;
        }
        String sql = """
                DELETE FROM session_attachment
                WHERE tenant_id = ? AND session_id = ? AND user_id = ? AND attachment_id = ?
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sessionId);
            statement.setString(3, userId);
            statement.setString(4, attachmentId);
            return statement.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw persist("附件无法删除");
        }
    }

    /**
     * 拼对象路径。三段都已经过 {@link #segment(String, int)}，用户文件名不在其中。
     *
     * @param tenantId 租户 id
     * @param sessionId 会话编号
     * @param attachmentId 附件编号
     * @return 以租户和会话为前缀的逻辑路径
     */
    private static String objectPath(String tenantId, String sessionId, String attachmentId) {
        return "tenant/" + tenantId + "/session/" + sessionId + "/attachment/" + attachmentId;
    }

    /**
     * 确认标识能当作一个路径分量。空白、点段、斜杠或超长时抛出持久化异常，调用方不得写库。
     *
     * @param value 候选标识
     * @param limit 最大字符数
     * @return 原标识
     */
    private static String segment(String value, int limit) {
        if (!valid(value, limit)) {
            throw persist("附件标识无效");
        }
        return value;
    }

    /**
     * 标识是否能当作一个路径分量。
     *
     * @param value 候选标识
     * @param limit 最大字符数
     * @return 合法时为真
     */
    private static boolean valid(String value, int limit) {
        return value != null && value.length() <= limit && !value.contains("..") && SEGMENT.matcher(value).matches();
    }

    /**
     * 从当前结果行读出摘要。列顺序必须与列表查询一致。
     *
     * @param rows 已经定位到一行的结果
     * @return 不含正文的摘要
     * @throws SQLException 驱动读列失败
     */
    private static Summary readSummary(ResultSet rows) throws SQLException {
        return new Summary(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getLong(4),
                rows.getString(5),
                rows.getString(6),
                rows.getString(7));
    }

    /**
     * 从当前结果行读出正文。列顺序必须与读取查询一致。
     *
     * @param rows 已经定位到一行的结果
     * @return 含正文的附件
     * @throws SQLException 驱动读列失败
     */
    private static Stored readStored(ResultSet rows) throws SQLException {
        byte[] content;
        try (InputStream in = rows.getBinaryStream(10)) {
            content = in == null ? new byte[0] : in.readAllBytes();
        } catch (java.io.IOException ex) {
            throw persist("附件无法读取");
        }
        return new Stored(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5),
                rows.getLong(6),
                rows.getString(7),
                rows.getString(8),
                rows.getString(9),
                content);
    }

    /**
     * 固定的持久化异常。消息不含口令、令牌或正文。
     *
     * @param message 给调用方的说明
     * @return 持久化异常
     */
    private static AgentException persist(String message) {
        return new AgentException(ErrorCode.PERSIST, message);
    }

    /**
     * 一条附件的摘要，不含正文。
     *
     * @param attachmentId 附件编号
     * @param sessionId 会话编号
     * @param mediaType 探测到的类型
     * @param byteSize 原始字节数
     * @param displayName 展示名，可能为空
     * @param objectPath 服务端生成的逻辑路径
     * @param createdAt 写入时间
     */
    public record Summary(
            String attachmentId,
            String sessionId,
            String mediaType,
            long byteSize,
            String displayName,
            String objectPath,
            String createdAt) {
        /**
         * 说明里不放正文、展示名或路径，避免日志带上用户文件。
         *
         * @return 只有类型和大小的说明
         */
        @Override
        public String toString() {
            return "Summary{mediaType=" + mediaType + ",byteSize=" + byteSize + "}";
        }
    }

    /**
     * 一条已经落库的附件，含正文。
     *
     * @param attachmentId 附件编号
     * @param sessionId 会话编号
     * @param tenantId 租户 id
     * @param userId 所有者登录名
     * @param mediaType 探测到的类型
     * @param byteSize 原始字节数
     * @param displayName 展示名，可能为空
     * @param objectPath 服务端生成的逻辑路径
     * @param createdAt 写入时间
     * @param content 原始字节
     */
    public record Stored(
            String attachmentId,
            String sessionId,
            String tenantId,
            String userId,
            String mediaType,
            long byteSize,
            String displayName,
            String objectPath,
            String createdAt,
            byte[] content) {
        /**
         * 说明里不放正文、展示名、路径或所有者，避免日志带上用户文件。
         *
         * @return 只有类型和大小的说明
         */
        @Override
        public String toString() {
            return "Stored{mediaType=" + mediaType + ",byteSize=" + byteSize + "}";
        }
    }
}
