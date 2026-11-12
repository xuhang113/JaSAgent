package dev.jasagent.gateway.store;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把用户到实例的粘性写进本机 jasagent 库的 {@code jasagent_gateway_affinity}。
 * 连接地址固定是 127.0.0.1:3306/jasagent。口令只从仓库外的口令文件读取，JDBC 地址和异常里都不放口令。
 */
public final class MysqlAffinityStore implements AffinityStore {
    /**
     * 本机 jasagent 库地址。短连接超时，关闭 SSL。地址里没有口令。
     */
    public static final String DEFAULT_URL = "jdbc:mysql://127.0.0.1:3306/jasagent"
            + "?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED";
    /**
     * 本机库的账号。口令不写在这里。
     */
    public static final String DEFAULT_USER = "root";
    /**
     * 建表语句。主键是租户加用户，保证一人一行。表里没有令牌和口令列。
     */
    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS jasagent_gateway_affinity (
              tenant_id VARCHAR(64) NOT NULL,
              user_id VARCHAR(128) NOT NULL,
              instance_id VARCHAR(64) NOT NULL,
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (tenant_id, user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private final String url;
    private final String user;
    private final String password;
    private final Object guard = new Object();
    private volatile boolean ready;

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    /**
     * 用显式地址和账号构造。地址里如果带了 password= 就拒绝，避免口令混进日志。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param password 数据库口令，只用于连接，不进入异常文本
     */
    public MysqlAffinityStore(String url, String user, String password) {
        if (url == null || url.toLowerCase().contains("password=")) {
            throw new AgentException(ErrorCode.PERSIST, "JDBC 地址不能携带口令");
        }
        this.url = url;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    /**
     * 打开本机 jasagent 库。口令文件为空时失败，失败说明里没有口令。
     *
     * @return 连到 127.0.0.1:3306/jasagent 的粘性库
     */
    public static MysqlAffinityStore openDefault() {
        String password = UragPassword.read();
        if (password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
        }
        return new MysqlAffinityStore(DEFAULT_URL, DEFAULT_USER, password);
    }

    /**
     * 查出粘性实例编号。没有行时为空。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     * @return 实例编号；没有记录时为空
     */
    @Override
    public Optional<String> find(String tenantId, String userId) {
        requireKey(tenantId, userId);
        ensure();
        String sql = "SELECT instance_id FROM jasagent_gateway_affinity WHERE tenant_id = ? AND user_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(rows.getString(1));
            }
        } catch (SQLException ex) {
            throw persist("粘性库读取失败");
        }
    }

    /**
     * 插入或改写粘性。同一租户和用户再次保存时只更新实例编号。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     * @param instanceId 实例编号
     */
    @Override
    public void save(String tenantId, String userId, String instanceId) {
        requireKey(tenantId, userId);
        if (instanceId == null || instanceId.isBlank() || instanceId.length() > 64) {
            throw new AgentException(ErrorCode.INTERNAL, "粘性键无效");
        }
        ensure();
        String sql = """
                INSERT INTO jasagent_gateway_affinity (tenant_id, user_id, instance_id)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE instance_id = VALUES(instance_id)
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, userId);
            statement.setString(3, instanceId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw persist("粘性库写入失败");
        }
    }

    /**
     * 列出这个租户的全部粘性，按用户 id 升序。
     *
     * @param tenantId 租户 id
     * @return 粘性行
     */
    @Override
    public List<AffinityRow> list(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 64) {
            throw new AgentException(ErrorCode.INTERNAL, "粘性键无效");
        }
        ensure();
        String sql = "SELECT user_id, instance_id FROM jasagent_gateway_affinity WHERE tenant_id = ? ORDER BY user_id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                List<AffinityRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new AffinityRow(rows.getString(1), rows.getString(2)));
                }
                return found;
            }
        } catch (SQLException ex) {
            throw persist("粘性库读取失败");
        }
    }

    /**
     * 删掉某个租户下的测试行。生产路径不调用。只按租户删除，不会动其它租户。
     *
     * @param tenantId 要清掉的租户 id
     */
    public void deleteTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        ensure();
        String sql = "DELETE FROM jasagent_gateway_affinity WHERE tenant_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw persist("粘性库写入失败");
        }
    }

    /**
     * 返回不含口令的说明。
     *
     * @return 只标明库位置
     */
    @Override
    public String toString() {
        return "MysqlAffinityStore{127.0.0.1:3306/jasagent}";
    }

    /**
     * 确认租户和用户可以放进主键列。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     */
    private static void requireKey(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 64
                || userId == null || userId.isBlank() || userId.length() > 128) {
            throw new AgentException(ErrorCode.INTERNAL, "粘性键无效");
        }
    }

    /**
     * 第一次使用时建表。失败说明不带数据库返回的原文，避免口令混进去。
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
                statement.execute(SCHEMA);
            } catch (SQLException ex) {
                throw persist("粘性库连接失败");
            }
            ready = true;
        }
    }

    /**
     * 打开一条短连接。口令只传给驱动，不拼进地址。
     *
     * @return 新连接
     * @throws SQLException 驱动拒绝连接时抛出，由调用方改写成不含口令的异常
     */
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * 把数据库失败收成固定说明。不附带原始异常，原始说明里可能出现口令。
     *
     * @param action 失败时给调用方的固定说明
     * @return 持久化异常
     */
    private static AgentException persist(String action) {
        return new AgentException(ErrorCode.PERSIST, action);
    }
}
