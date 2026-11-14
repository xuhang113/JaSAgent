package dev.jasagent.gateway.store;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.file.Path;
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
 * 把用户到实例的粘性写进 {@code jasagent_gateway_affinity}。
 * 地址和账号来自配置，默认仍是 127.0.0.1:3306/jasagent 和 root。口令只从仓库外的口令文件读取。
 * JDBC 地址、异常、日志和 {@link #toString()} 都不放口令或令牌。表结构这次不改。
 */
public final class MysqlAffinityStore implements AffinityStore {
    /**
     * JDBC 地址的配置项。值里不能出现 password=。
     */
    public static final String URL_PROPERTY = "jasagent.gateway.affinity.jdbc-url";
    /**
     * 数据库账号的配置项。口令不写在这个配置项里。
     */
    public static final String USER_PROPERTY = "jasagent.gateway.affinity.username";
    /**
     * 口令文件路径的配置项。文件在仓库外，默认是用户主目录下的 .config/urag-mysql-root.password。
     */
    public static final String PASSWORD_FILE_PROPERTY = "jasagent.gateway.affinity.password-file";
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
        if (url == null || url.isBlank() || url.toLowerCase().contains("password=")) {
            throw new AgentException(ErrorCode.PERSIST, "JDBC 地址不能携带口令");
        }
        this.url = url;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    /**
     * 用配置里的地址、账号和仓库外口令文件打开粘性库。地址含 password= 或口令文件为空时失败，说明里没有口令。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param passwordFile 仓库外的口令文件
     * @return 连到该地址的粘性库
     */
    public static MysqlAffinityStore open(String url, String user, Path passwordFile) {
        if (url == null || url.isBlank() || url.toLowerCase().contains("password=")) {
            throw new AgentException(ErrorCode.PERSIST, "JDBC 地址不能携带口令");
        }
        String password = UragPassword.read(passwordFile);
        if (password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
        }
        return new MysqlAffinityStore(url, user, password);
    }

    /**
     * 打开默认的本机 jasagent 库。口令仍从默认的仓库外文件读取。
     *
     * @return 连到 127.0.0.1:3306/jasagent 的粘性库
     */
    public static MysqlAffinityStore openDefault() {
        return open(DEFAULT_URL, DEFAULT_USER, UragPassword.defaultFile());
    }

    /**
     * 实际交给驱动的 JDBC 地址。配置了别的主机或库名时，这里就是那个地址，不会改回 127.0.0.1:3306/jasagent。
     *
     * @return 不含口令的 JDBC 地址
     */
    public String jdbcUrl() {
        return url;
    }

    /**
     * 实际使用的数据库账号。口令不从这里返回。
     *
     * @return 配置的账号
     */
    public String username() {
        return user;
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
     * 返回不含口令的说明。主机、端口和库名取自实际使用的地址。
     *
     * @return 只标明库位置
     */
    @Override
    public String toString() {
        return "MysqlAffinityStore{" + location(url) + "}";
    }

    /**
     * 从 JDBC 地址里取出主机、端口和库名。去掉用户信息和查询串，避免口令混进文本。
     *
     * @param jdbcUrl 已经拒绝过 password= 的地址
     * @return host:port/database
     */
    static String location(String jdbcUrl) {
        String rest = jdbcUrl == null ? "" : jdbcUrl;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        String database = slash >= 0 ? rest.substring(slash + 1) : "";
        int query = database.indexOf('?');
        if (query >= 0) {
            database = database.substring(0, query);
        }
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) {
            hostPort = hostPort.substring(at + 1);
        }
        return hostPort + "/" + database;
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
     * 打开一条短连接。地址和账号用构造时的配置，口令只传给驱动，不拼进地址。
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
