package dev.jasagent.agentpass.durable;

import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * 通行证的证状态、流水、策略和待批，以及发证前要核对的 020 账号和登录会话。
 * 连接地址不能带 password=。异常和 {@link #toString()} 不放口令、令牌、证明文或签名密钥。
 */
public final class AgentPassLedger {
    /**
     * 写入账号表口令列的占位。不是口令，也不能用来登录。
     */
    static final String PASSWORD_PLACEHOLDER = "not-used";
    /**
     * 流水排序号。同一毫秒内的多次写入仍按调用顺序排列。
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis() * 1000L);
    /**
     * MySQL 建表语句末尾。H2 测试会把它去掉。
     */
    private static final String ENGINE = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private final String url;
    private final String user;
    private final String password;
    private final boolean mysql;
    private final Object guard = new Object();
    private volatile boolean ready;

    /**
     * 记下连接。地址里如果带了 password= 就拒绝。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param password 数据库口令，只交给驱动
     */
    private AgentPassLedger(String url, String user, String password) {
        if (url == null || url.isBlank() || url.toLowerCase().contains("password=")) {
            throw new IllegalStateException("JDBC 地址不能携带口令");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("没有找到本机 MySQL 口令");
        }
        this.url = url;
        this.user = user == null || user.isBlank() ? "root" : user;
        this.password = password;
        this.mysql = url.startsWith("jdbc:mysql:");
    }

    /**
     * 打开账本并建好本轮需要的表。020 的账号表已存在时不会改它的列。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param password 数据库口令
     * @return 已建表的账本
     */
    public static AgentPassLedger open(String url, String user, String password) {
        loadDriver(url);
        AgentPassLedger ledger = new AgentPassLedger(url, user, password);
        ledger.ensure();
        return ledger;
    }

    /**
     * 按地址装载驱动。MySQL 驱动缺失时失败说明不带地址里的参数。
     *
     * @param url JDBC 地址
     */
    private static void loadDriver(String url) {
        try {
            if (url != null && url.startsWith("jdbc:h2:")) {
                Class.forName("org.h2.Driver");
                return;
            }
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("agentpass jdbc driver");
        }
    }

    /**
     * 不含口令的 JDBC 地址。
     *
     * @return JDBC 地址
     */
    public String url() {
        return url;
    }

    /**
     * 数据库账号。口令不从这里返回。
     *
     * @return 账号
     */
    public String user() {
        return user;
    }

    /**
     * 写入或更新一个 020 账号。口令列只放占位，不接收口令原文。
     * 副作用：插入或改写账号行。
     *
     * @param login 登录名
     * @param tenantId 该用户独占的租户
     */
    public void saveAccount(String login, String tenantId) {
        ensure();
        String sql = "INSERT INTO " + AgentPassTables.ACCOUNT
                + " (login, password_hash, role, tenant_id, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, login);
            statement.setString(2, PASSWORD_PLACEHOLDER);
            statement.setString(3, "user");
            statement.setString(4, tenantId);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            if (!duplicate(ex)) {
                throw failure();
            }
            String update = "UPDATE " + AgentPassTables.ACCOUNT + " SET tenant_id = ? WHERE login = ?";
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, tenantId);
                statement.setString(2, login);
                statement.executeUpdate();
            } catch (SQLException again) {
                throw failure();
            }
        }
    }

    /**
     * 写入一条登录会话。令牌只以 SHA-256 入库，调用方仍持有原文。
     * 副作用：插入或改写会话行。
     *
     * @param login 登录名，必须已经有账号
     * @param sessionId 会话编号
     * @param token 登录令牌原文，不写入日志
     */
    public void saveLogin(String login, String sessionId, String token) {
        ensure();
        String sql = "INSERT INTO " + AgentPassTables.LOGIN_SESSION
                + " (session_id, token_hash, login, workspace, created_at) VALUES (?, ?, ?, NULL, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, tokenHash(token));
            statement.setString(3, login);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            if (!duplicate(ex)) {
                throw failure();
            }
            String update = "UPDATE " + AgentPassTables.LOGIN_SESSION + " SET token_hash = ?, login = ? WHERE session_id = ?";
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, tokenHash(token));
                statement.setString(2, login);
                statement.setString(3, sessionId);
                statement.executeUpdate();
            } catch (SQLException again) {
                throw failure();
            }
        }
    }

    /**
     * 按会话编号读取登录名和租户。会话不存在时为空。
     *
     * @param sessionId 登录会话编号
     * @return 会话身份；没有时为空
     */
    public Identity identity(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        ensure();
        String sql = "SELECT s.session_id, s.login, a.tenant_id FROM " + AgentPassTables.LOGIN_SESSION
                + " s JOIN " + AgentPassTables.ACCOUNT + " a ON a.login = s.login WHERE s.session_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new Identity(rows.getString(1), rows.getString(2), rows.getString(3));
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 按登录令牌找回会话。令牌对不上时为空，不把原文放进异常。
     *
     * @param token 登录令牌原文
     * @return 会话身份；无效时为空
     */
    public Identity authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        ensure();
        String sql = "SELECT s.session_id, s.login, a.tenant_id FROM " + AgentPassTables.LOGIN_SESSION
                + " s JOIN " + AgentPassTables.ACCOUNT + " a ON a.login = s.login WHERE s.token_hash = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash(token));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new Identity(rows.getString(1), rows.getString(2), rows.getString(3));
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 按登录名读取租户。没有账号时为空。
     *
     * @param login 登录名
     * @return 租户 id；没有账号时为空
     */
    public String tenantOf(String login) {
        if (login == null || login.isBlank()) {
            return null;
        }
        ensure();
        String sql = "SELECT tenant_id FROM " + AgentPassTables.ACCOUNT + " WHERE login = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, login);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return rows.getString(1);
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 写入或改写一条租户策略。决定只能是 ALLOW 或 DENY。
     * 副作用：插入或更新策略行。
     *
     * @param tenantId 租户
     * @param kind tool 或 plugin
     * @param subject 工具名或插件标识
     * @param decision ALLOW 或 DENY
     */
    public void savePolicy(String tenantId, String kind, String subject, String decision) {
        ensure();
        String sql = "INSERT INTO " + AgentPassTables.POLICY + " (tenant_id, kind, subject, decision) VALUES (?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, kind);
            statement.setString(3, clip(subject, 128));
            statement.setString(4, decision);
            statement.executeUpdate();
        } catch (SQLException ex) {
            if (!duplicate(ex)) {
                throw failure();
            }
            String update = "UPDATE " + AgentPassTables.POLICY + " SET decision = ? WHERE tenant_id = ? AND kind = ? AND subject = ?";
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, decision);
                statement.setString(2, tenantId);
                statement.setString(3, kind);
                statement.setString(4, clip(subject, 128));
                statement.executeUpdate();
            } catch (SQLException again) {
                throw failure();
            }
        }
    }

    /**
     * 读取一条策略的决定。没有这条规则时为空。
     *
     * @param tenantId 租户
     * @param kind tool 或 plugin
     * @param subject 工具名或插件标识
     * @return ALLOW 或 DENY；未配置时为空
     */
    public String policy(String tenantId, String kind, String subject) {
        ensure();
        String sql = "SELECT decision FROM " + AgentPassTables.POLICY + " WHERE tenant_id = ? AND kind = ? AND subject = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, kind);
            statement.setString(3, clip(subject, 128));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return rows.getString(1);
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 读取某次执行上已有待批或已决记录的决定。没有记录时为空。
     *
     * @param tenantId 租户
     * @param userId 登录名
     * @param executionId 执行编号
     * @param toolName 工具名，没有时为空串
     * @param pluginId 插件标识，没有时为空串
     * @return PENDING、ALLOW 或 DENY；没有行时为空
     */
    public String approval(String tenantId, String userId, String executionId, String toolName, String pluginId) {
        Pass pass = findCall(tenantId, userId, executionId, toolName, pluginId);
        return pass == null ? null : pass.decision().name();
    }

    /**
     * 留下或取回同一次调用的待批。已有行时不另建编号。
     * 副作用：没有行时插入一条 PENDING。
     *
     * @param tenantId 租户
     * @param userId 登录名
     * @param sessionId 登录会话编号，可以是空串
     * @param executionId 执行编号
     * @param toolName 工具名，没有时为空串
     * @param pluginId 插件标识，没有时为空串
     * @param target 领证目标，进程内申请可以是空串
     * @return 待批通行证，决定是 PENDING，或已经作出的决定
     */
    public Pass ensurePending(String tenantId, String userId, String sessionId, String executionId, String toolName,
                              String pluginId, String target) {
        Pass existing = findCall(tenantId, userId, executionId, toolName, pluginId);
        if (existing != null) {
            return existing;
        }
        String passId = UUID.randomUUID().toString();
        String sql = "INSERT INTO " + AgentPassTables.PENDING + """
                 (pass_id, tenant_id, user_id, session_id, execution_id, tool_name, plugin_id, target, decision, reason, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, passId);
            statement.setString(2, tenantId);
            statement.setString(3, userId);
            statement.setString(4, clip(sessionId, 64));
            statement.setString(5, clip(executionId, 128));
            statement.setString(6, clip(toolName, 128));
            statement.setString(7, clip(pluginId, 128));
            statement.setString(8, clip(target, 512));
            statement.setString(9, PassDecision.PENDING.name());
            statement.setString(10, TenantPassPolicy.REASON_PENDING);
            statement.setString(11, Instant.now().toString());
            statement.executeUpdate();
            return passOf(passId, userId, executionId, toolName, pluginId, PassDecision.PENDING, TenantPassPolicy.REASON_PENDING);
        } catch (SQLException ex) {
            if (!duplicate(ex)) {
                throw failure();
            }
            Pass again = findCall(tenantId, userId, executionId, toolName, pluginId);
            if (again == null) {
                throw failure();
            }
            return again;
        }
    }

    /**
     * 按编号读取待批。没有时为空。
     *
     * @param passId 通行证编号
     * @return 待批或已决通行证；没有时为空
     */
    public Pass findPending(String passId) {
        ensure();
        String sql = "SELECT pass_id, user_id, execution_id, tool_name, plugin_id, decision, reason FROM "
                + AgentPassTables.PENDING + " WHERE pass_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, passId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return passOf(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
                        PassDecision.valueOf(rows.getString(6)), rows.getString(7));
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 统计某次执行上的待批行数，含已经批准或拒绝的行。
     *
     * @param executionId 执行编号
     * @return 行数
     */
    public int countPending(String executionId) {
        ensure();
        String sql = "SELECT COUNT(*) FROM " + AgentPassTables.PENDING + " WHERE execution_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 同一用户把待批改成允许或拒绝。别人的编号、别的执行或已经决定过的行都不会改。
     * 副作用：成功时更新决定和决定时间。
     *
     * @param userId 作出决定的登录名
     * @param executionId 执行编号
     * @param passId 通行证编号
     * @param decision 只能是 ALLOW 或 DENY
     * @return 更新结果
     */
    public DecisionOutcome decide(String userId, String executionId, String passId, PassDecision decision) {
        ensure();
        String sql = "SELECT user_id, execution_id, decision FROM " + AgentPassTables.PENDING
                + " WHERE pass_id = ? FOR UPDATE";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, passId);
                String owner;
                String execution;
                String current;
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        connection.rollback();
                        return DecisionOutcome.MISSING;
                    }
                    owner = rows.getString(1);
                    execution = rows.getString(2);
                    current = rows.getString(3);
                }
                if (!owner.equals(userId) || !execution.equals(executionId)) {
                    connection.rollback();
                    return DecisionOutcome.FORBIDDEN;
                }
                if (!PassDecision.PENDING.name().equals(current)) {
                    connection.rollback();
                    return DecisionOutcome.ALREADY;
                }
                String update = "UPDATE " + AgentPassTables.PENDING
                        + " SET decision = ?, reason = ?, decided_at = ? WHERE pass_id = ?";
                try (PreparedStatement write = connection.prepareStatement(update)) {
                    write.setString(1, decision.name());
                    write.setString(2, decision == PassDecision.ALLOW ? TenantPassPolicy.REASON_APPROVED : TenantPassPolicy.REASON_DENIED);
                    write.setString(3, Instant.now().toString());
                    write.setString(4, passId);
                    write.executeUpdate();
                }
                connection.commit();
                return DecisionOutcome.UPDATED;
            } catch (SQLException ex) {
                connection.rollback();
                throw failure();
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 保存一张新签发的证。编号已存在时改写绑定和未使用标记。
     * 副作用：插入或更新证状态行。
     *
     * @param passId 通行证编号
     * @param tenant 租户
     * @param user 登录名
     * @param sessionId 会话编号
     * @param executionId 执行编号
     * @param target 目标
     * @param toolName 工具名
     * @param expiresAt 过期时刻
     * @param used 已核销时为真
     */
    public void saveTicket(String passId, String tenant, String user, String sessionId, String executionId, String target,
                           String toolName, Instant expiresAt, boolean used) {
        ensure();
        String sql = "INSERT INTO " + AgentPassTables.TICKET + """
                 (pass_id, tenant_id, user_id, session_id, execution_id, target_service, tool_name, expires_at, used)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindTicket(statement, passId, tenant, user, sessionId, executionId, target, toolName, expiresAt, used);
            statement.executeUpdate();
        } catch (SQLException ex) {
            if (!duplicate(ex)) {
                throw failure();
            }
            updateTicket(passId, tenant, user, sessionId, executionId, target, toolName, expiresAt, used);
        }
    }

    /**
     * 按编号读取证。没有签发过时为空。过期文本无法解析时也视为没有，避免把原文放进异常。
     *
     * @param passId 通行证编号
     * @return 证状态；没有时为空
     */
    public StoredTicket findTicket(String passId) {
        ensure();
        String sql = "SELECT pass_id, tenant_id, user_id, session_id, execution_id, target_service, tool_name, expires_at, used FROM "
                + AgentPassTables.TICKET + " WHERE pass_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, passId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return readTicket(rows);
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 在一行锁里读取并替换证状态。用来保证只有第一次核销成功。
     * 副作用：计算函数返回的新状态会写回；返回空则删掉该编号。
     *
     * @param passId 通行证编号
     * @param updater 根据当前状态算出下一个状态；当前为空表示还没有这张证
     * @return 写回之后的状态；被删除或不存在时为空
     */
    public StoredTicket computeTicket(String passId, BiFunction<String, StoredTicket, StoredTicket> updater) {
        ensure();
        String sql = "SELECT pass_id, tenant_id, user_id, session_id, execution_id, target_service, tool_name, expires_at, used FROM "
                + AgentPassTables.TICKET + " WHERE pass_id = ? FOR UPDATE";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, passId);
                StoredTicket current;
                try (ResultSet rows = statement.executeQuery()) {
                    current = rows.next() ? readTicket(rows) : null;
                }
                StoredTicket next = updater.apply(passId, current);
                if (next == null) {
                    if (current != null) {
                        try (PreparedStatement delete = connection.prepareStatement(
                                "DELETE FROM " + AgentPassTables.TICKET + " WHERE pass_id = ?")) {
                            delete.setString(1, passId);
                            delete.executeUpdate();
                        }
                    }
                } else if (current == null) {
                    insertTicket(connection, next);
                } else {
                    updateTicket(connection, next);
                }
                connection.commit();
                return next;
            } catch (SQLException ex) {
                connection.rollback();
                throw failure();
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 当前库里的证张数，包括已核销和已过期的。
     *
     * @return 行数
     */
    public int countTickets() {
        ensure();
        String sql = "SELECT COUNT(*) FROM " + AgentPassTables.TICKET;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 追加一条流水。字段在入库前截断，不写令牌或证明文。
     * 副作用：流水表增加一行。
     *
     * @param kind 事件种类名
     * @param passId 通行证编号，没有时为空串
     * @param tenant 租户
     * @param user 登录名
     * @param executionId 执行编号
     * @param target 目标
     * @param toolName 工具名
     * @param reason 原因码
     * @param at 发生时刻
     */
    public void appendAudit(String kind, String passId, String tenant, String user, String executionId, String target,
                            String toolName, String reason, Instant at) {
        ensure();
        String sql = "INSERT INTO " + AgentPassTables.AUDIT + """
                 (event_id, seq, kind, pass_id, tenant_id, user_id, execution_id, target_service, tool_name, reason, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setLong(2, SEQUENCE.incrementAndGet());
            statement.setString(3, clip(kind, 16));
            statement.setString(4, clip(passId, 64));
            statement.setString(5, clip(tenant, 128));
            statement.setString(6, clip(user, 128));
            statement.setString(7, clip(executionId, 128));
            statement.setString(8, clip(target, 512));
            statement.setString(9, clip(toolName, 128));
            statement.setString(10, clip(reason, 128));
            statement.setString(11, at.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 按写入顺序返回一个租户的流水。别的租户的行不会出现。
     *
     * @param tenantId 租户
     * @return 该租户的流水
     */
    public List<StoredAudit> auditForTenant(String tenantId) {
        return auditWhere(" WHERE tenant_id = ?", tenantId);
    }

    /**
     * 按写入顺序返回全部流水。HTTP 查询不能调用它，只给同进程测试核对。
     *
     * @return 全部流水
     */
    public List<StoredAudit> auditAll() {
        return auditWhere("", null);
    }

    /**
     * 删掉这个登录名的账号、会话、策略、待批、证和流水。只用于测试收尾。
     * 副作用：删除这些行。
     *
     * @param login 登录名
     */
    public void deleteUser(String login) {
        if (login == null || login.isBlank()) {
            return;
        }
        String tenant = tenantOf(login);
        ensure();
        try (Connection connection = connect()) {
            delete(connection, "DELETE FROM " + AgentPassTables.AUDIT + " WHERE user_id = ?", login);
            delete(connection, "DELETE FROM " + AgentPassTables.TICKET + " WHERE user_id = ?", login);
            delete(connection, "DELETE FROM " + AgentPassTables.PENDING + " WHERE user_id = ?", login);
            if (tenant != null) {
                delete(connection, "DELETE FROM " + AgentPassTables.POLICY + " WHERE tenant_id = ?", tenant);
            }
            delete(connection, "DELETE FROM " + AgentPassTables.LOGIN_SESSION + " WHERE login = ?", login);
            delete(connection, "DELETE FROM " + AgentPassTables.ACCOUNT + " WHERE login = ?", login);
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 不含口令的说明。
     *
     * @return 地址和账号
     */
    @Override
    public String toString() {
        return "AgentPassLedger[url=" + url + ", user=" + user + "]";
    }

    /**
     * 第一次使用时建表。失败说明不带驱动返回的原文。
     */
    private void ensure() {
        if (ready) {
            return;
        }
        synchronized (guard) {
            if (ready) {
                return;
            }
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.execute(ddl(AgentPassTables.ACCOUNT_DDL));
                statement.execute(ddl(AgentPassTables.LOGIN_SESSION_DDL));
                statement.execute(ddl(AgentPassTables.POLICY_DDL));
                statement.execute(ddl(AgentPassTables.PENDING_DDL));
                statement.execute(ddl(AgentPassTables.TICKET_DDL));
                statement.execute(ddl(AgentPassTables.AUDIT_DDL));
            } catch (SQLException ex) {
                throw failure();
            }
            ready = true;
        }
    }

    /**
     * 打开一条新连接。口令只传给驱动。
     *
     * @return JDBC 连接
     */
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * H2 不接受 MySQL 的引擎子句，这里只在非 MySQL 地址上去掉它。
     *
     * @param sql 建表语句
     * @return 当前库可以执行的语句
     */
    private String ddl(String sql) {
        if (mysql) {
            return sql;
        }
        return sql.replace(ENGINE, "");
    }

    /**
     * 按条件读取流水并排序。参数为空时不加过滤。
     *
     * @param where 已含 WHERE 或空串
     * @param tenant 过滤租户；不过滤时为空
     * @return 流水
     */
    private List<StoredAudit> auditWhere(String where, String tenant) {
        ensure();
        String sql = "SELECT kind, pass_id, tenant_id, user_id, execution_id, target_service, tool_name, reason, created_at FROM "
                + AgentPassTables.AUDIT + where + " ORDER BY seq";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (tenant != null) {
                statement.setString(1, tenant);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<StoredAudit> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new StoredAudit(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                            Instant.parse(rows.getString(9))));
                }
                return List.copyOf(found);
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 按同一次调用查找待批。没有时为空。
     *
     * @param tenantId 租户
     * @param userId 登录名
     * @param executionId 执行编号
     * @param toolName 工具名
     * @param pluginId 插件标识
     * @return 通行证；没有时为空
     */
    private Pass findCall(String tenantId, String userId, String executionId, String toolName, String pluginId) {
        ensure();
        String sql = "SELECT pass_id, user_id, execution_id, tool_name, plugin_id, decision, reason FROM "
                + AgentPassTables.PENDING
                + " WHERE tenant_id = ? AND user_id = ? AND execution_id = ? AND tool_name = ? AND plugin_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, userId);
            statement.setString(3, clip(executionId, 128));
            statement.setString(4, clip(toolName, 128));
            statement.setString(5, clip(pluginId, 128));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return passOf(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
                        PassDecision.valueOf(rows.getString(6)), rows.getString(7));
            }
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 改写已有证。只在主键冲突后调用。
     */
    private void updateTicket(String passId, String tenant, String user, String sessionId, String executionId, String target,
                              String toolName, Instant expiresAt, boolean used) {
        String sql = "UPDATE " + AgentPassTables.TICKET + """
                 SET tenant_id = ?, user_id = ?, session_id = ?, execution_id = ?, target_service = ?, tool_name = ?, expires_at = ?, used = ?
                 WHERE pass_id = ?
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clip(tenant, 128));
            statement.setString(2, clip(user, 128));
            statement.setString(3, clip(sessionId, 128));
            statement.setString(4, clip(executionId, 128));
            statement.setString(5, clip(target, 512));
            statement.setString(6, clip(toolName, 128));
            statement.setString(7, expiresAt.toString());
            statement.setInt(8, used ? 1 : 0);
            statement.setString(9, passId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw failure();
        }
    }

    /**
     * 在已打开的事务里插入证。
     *
     * @param connection 当前事务
     * @param ticket 要写入的状态
     */
    private void insertTicket(Connection connection, StoredTicket ticket) throws SQLException {
        String sql = "INSERT INTO " + AgentPassTables.TICKET + """
                 (pass_id, tenant_id, user_id, session_id, execution_id, target_service, tool_name, expires_at, used)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindTicket(statement, ticket.passId(), ticket.tenant(), ticket.user(), ticket.sessionId(), ticket.executionId(),
                    ticket.target(), ticket.toolName(), ticket.expiresAt(), ticket.used());
            statement.executeUpdate();
        }
    }

    /**
     * 在已打开的事务里更新证。
     *
     * @param connection 当前事务
     * @param ticket 要写回的状态
     */
    private void updateTicket(Connection connection, StoredTicket ticket) throws SQLException {
        String sql = "UPDATE " + AgentPassTables.TICKET + """
                 SET tenant_id = ?, user_id = ?, session_id = ?, execution_id = ?, target_service = ?, tool_name = ?, expires_at = ?, used = ?
                 WHERE pass_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clip(ticket.tenant(), 128));
            statement.setString(2, clip(ticket.user(), 128));
            statement.setString(3, clip(ticket.sessionId(), 128));
            statement.setString(4, clip(ticket.executionId(), 128));
            statement.setString(5, clip(ticket.target(), 512));
            statement.setString(6, clip(ticket.toolName(), 128));
            statement.setString(7, ticket.expiresAt().toString());
            statement.setInt(8, ticket.used() ? 1 : 0);
            statement.setString(9, ticket.passId());
            statement.executeUpdate();
        }
    }

    /**
     * 给插入证的语句填上绑定字段。
     */
    private static void bindTicket(PreparedStatement statement, String passId, String tenant, String user, String sessionId,
                                   String executionId, String target, String toolName, Instant expiresAt, boolean used)
            throws SQLException {
        statement.setString(1, passId);
        statement.setString(2, clip(tenant, 128));
        statement.setString(3, clip(user, 128));
        statement.setString(4, clip(sessionId, 128));
        statement.setString(5, clip(executionId, 128));
        statement.setString(6, clip(target, 512));
        statement.setString(7, clip(toolName, 128));
        statement.setString(8, expiresAt.toString());
        statement.setInt(9, used ? 1 : 0);
    }

    /**
     * 从当前行还原证。过期文本无法解析时返回空，调用方按没有这张证处理。
     *
     * @param rows 已经定位到一行的结果
     * @return 证状态；过期时刻损坏时为空
     */
    private static StoredTicket readTicket(ResultSet rows) throws SQLException {
        try {
            return new StoredTicket(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                    rows.getString(5), rows.getString(6), rows.getString(7), Instant.parse(rows.getString(8)),
                    rows.getInt(9) != 0);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 执行一条按登录名或租户删除的语句。
     *
     * @param connection 当前连接
     * @param sql 带一个参数的删除语句
     * @param value 登录名或租户
     */
    private static void delete(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    /**
     * 组装通行证。工具名或插件标识为空白时留空。
     */
    private static Pass passOf(String passId, String userId, String executionId, String toolName, String pluginId,
                               PassDecision decision, String reason) {
        String tool = toolName == null || toolName.isBlank() ? null : toolName;
        String plugin = pluginId == null || pluginId.isBlank() ? null : pluginId;
        return new Pass(passId, userId, executionId, tool, plugin, "", decision, reason == null ? "" : reason);
    }

    /**
     * 登录令牌的 SHA-256 十六进制。算法与 020 账号库一致，原文不进入异常。
     *
     * @param token 令牌原文
     * @return 64 位十六进制
     */
    static String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("agentpass session");
        }
    }

    /**
     * 去掉换行并截断，避免超长字段撑满列或把多行内容写进库。
     *
     * @param value 原始文本
     * @param max 最大字符数
     * @return 单行文本；空输入变成空串
     */
    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    /**
     * 主键或唯一键冲突。两种库的错误码不同，状态码 23000 两边都能碰到。
     *
     * @param ex 驱动异常
     * @return 是重复键时为真
     */
    private static boolean duplicate(SQLException ex) {
        return ex.getErrorCode() == 1062 || ex.getErrorCode() == 23505 || "23000".equals(ex.getSQLState());
    }

    /**
     * 固定失败说明。不链接驱动异常，避免说明里带上参数或地址。
     *
     * @return 不含凭证的异常
     */
    private static IllegalStateException failure() {
        return new IllegalStateException("agentpass store");
    }

    /**
     * 发证前从 020 表读到的登录身份。
     *
     * @param sessionId 登录会话编号
     * @param login 登录名
     * @param tenantId 该登录名独占的租户
     */
    public record Identity(String sessionId, String login, String tenantId) {
    }

    /**
     * 待批决定的四种结局。
     */
    public enum DecisionOutcome {
        /**
         * 同一用户把等待中的记录改成了允许或拒绝。
         */
        UPDATED,
        /**
         * 没有这张待批。
         */
        MISSING,
        /**
         * 用户或执行对不上，不能代批。
         */
        FORBIDDEN,
        /**
         * 这张待批已经决定过。
         */
        ALREADY
    }

    /**
     * 库里的一张证。字段和签发时的绑定一致。
     *
     * @param passId 通行证编号
     * @param tenant 租户
     * @param user 登录名
     * @param sessionId 会话编号
     * @param executionId 执行编号
     * @param target 目标
     * @param toolName 工具名
     * @param expiresAt 过期时刻
     * @param used 已经核销时为真
     */
    public record StoredTicket(
            String passId,
            String tenant,
            String user,
            String sessionId,
            String executionId,
            String target,
            String toolName,
            Instant expiresAt,
            boolean used) {
    }

    /**
     * 库里的一条流水。不含令牌和证明文。
     *
     * @param kind 种类名
     * @param passId 通行证编号
     * @param tenant 租户
     * @param user 登录名
     * @param executionId 执行编号
     * @param target 目标
     * @param toolName 工具名
     * @param reason 原因码
     * @param at 发生时刻
     */
    public record StoredAudit(
            String kind,
            String passId,
            String tenant,
            String user,
            String executionId,
            String target,
            String toolName,
            String reason,
            Instant at) {
    }
}
