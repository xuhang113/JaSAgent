package dev.jasagent.gateway.agent;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.store.UragPassword;
import org.springframework.beans.factory.DisposableBean;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 把每台 agent 的长连接存活按网关副本写进已有库 jasagent 的 {@code jasagent_gateway_liveness}。
 * 地址、账号和口令文件与粘性库相同，不新建库，也不另配一套 JDBC。
 * 每个进程用自己的副本编号写心跳。一个副本断开只把自己的连接标记清掉，其它副本的近期心跳还在。
 * 过期仍标着已连接的心跳不再算存活，避免崩溃的副本把 agent 一直标成在线。
 * 登录会话在 MySQL。存活探测按网关副本写入同一库并跨副本读取。本机缺省副本数仍是 1。
 */
public final class MysqlAgentLiveness implements AgentLiveness, DisposableBean {
    /**
     * 心跳晚于三倍探测间隔就不再把该副本的连接算作存活。长连接本身仍按 {@link AgentLiveness#HEARTBEAT_INTERVAL} 写。
     */
    public static final Duration FRESHNESS = HEARTBEAT_INTERVAL.multipliedBy(3);
    /**
     * 副本编号。字母或数字开头，只含字母、数字、点、下划线和连字符，最长 64。
     */
    private static final Pattern REPLICA_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");
    /**
     * 建表语句。一行是一个网关副本看到的一台 agent。表里没有口令或令牌列。
     */
    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS jasagent_gateway_liveness (
              replica_id VARCHAR(64) NOT NULL,
              instance_id VARCHAR(64) NOT NULL,
              connected TINYINT NOT NULL,
              last_heartbeat_ms BIGINT NULL,
              PRIMARY KEY (replica_id, instance_id),
              KEY idx_jasagent_gateway_liveness_instance (instance_id, connected, last_heartbeat_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private final String url;
    private final String user;
    private final String password;
    private final String replicaId;
    private final Duration freshness;
    private final Supplier<Instant> clock;
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
     * 用显式地址、账号、副本编号和时钟构造。地址里如果带了 password= 就拒绝。
     * 时钟决定新鲜度，测试可以把它停在某一刻；生产用当前时刻。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param password 数据库口令，只用于连接，不进入异常文本
     * @param replicaId 本进程的副本编号，不与其它副本相同
     * @param freshness 已连接心跳的有效期，必须为正
     * @param clock 当前时刻；为空时拒绝
     */
    public MysqlAgentLiveness(String url, String user, String password, String replicaId, Duration freshness, Supplier<Instant> clock) {
        if (url == null || url.isBlank() || url.toLowerCase().contains("password=")) {
            throw new AgentException(ErrorCode.PERSIST, "JDBC 地址不能携带口令");
        }
        if (password == null || password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
        }
        if (freshness == null || freshness.isZero() || freshness.isNegative() || clock == null) {
            throw new AgentException(ErrorCode.INTERNAL, "存活新鲜度无效");
        }
        this.url = url;
        this.user = user == null ? "" : user;
        this.password = password;
        this.replicaId = requireReplica(replicaId);
        this.freshness = freshness;
        this.clock = clock;
    }

    /**
     * 用和粘性库相同的地址、账号、口令文件打开，并分配一个新的副本编号。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param passwordFile 仓库外的口令文件
     * @return 本进程这一份存活表
     */
    public static MysqlAgentLiveness open(String url, String user, Path passwordFile) {
        return open(url, user, passwordFile, newReplicaId());
    }

    /**
     * 用指定副本编号打开。两个编号就是两个副本，读到的是同一张表。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param passwordFile 仓库外的口令文件
     * @param replicaId 本副本编号
     * @return 绑定该编号的存活表
     */
    public static MysqlAgentLiveness open(String url, String user, Path passwordFile, String replicaId) {
        if (url == null || url.isBlank() || url.toLowerCase().contains("password=")) {
            throw new AgentException(ErrorCode.PERSIST, "JDBC 地址不能携带口令");
        }
        String password = UragPassword.read(passwordFile);
        if (password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
        }
        return new MysqlAgentLiveness(url, user, password, replicaId, FRESHNESS, Instant::now);
    }

    /**
     * 打开默认的本机 jasagent 库，副本编号新生成。口令仍从默认的仓库外文件读取。
     *
     * @return 连到 127.0.0.1:3306/jasagent 的存活表
     */
    public static MysqlAgentLiveness openDefault() {
        return open(MysqlAffinityStore.DEFAULT_URL, MysqlAffinityStore.DEFAULT_USER, UragPassword.defaultFile());
    }

    /**
     * 生成本进程的副本编号。不读取主机名或环境变量，避免把部署信息写进编号。
     *
     * @return 符合 {@link #requireReplica(String)} 的编号
     */
    public static String newReplicaId() {
        return "gw-" + UUID.randomUUID();
    }

    /**
     * 实际交给驱动的 JDBC 地址。
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
     * 本进程写入时使用的副本编号。两个存活表编号不同，就表示两个副本。
     *
     * @return 副本编号
     */
    public String replicaId() {
        return replicaId;
    }

    /**
     * 本副本的长连接已经建立，并记下当前时刻的心跳。之后 {@link #connectionAlive(AgentInstance)} 在有效期内为真。
     *
     * @param instance 刚连上的 agent
     */
    @Override
    public void markUp(AgentInstance instance) {
        String instanceId = requireInstance(instance);
        long beat = now().toEpochMilli();
        ensure();
        String sql = """
                INSERT INTO jasagent_gateway_liveness (replica_id, instance_id, connected, last_heartbeat_ms)
                VALUES (?, ?, 1, ?)
                ON DUPLICATE KEY UPDATE connected = 1, last_heartbeat_ms = VALUES(last_heartbeat_ms)
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replicaId);
            statement.setString(2, instanceId);
            statement.setLong(3, beat);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw persist("存活库写入失败");
        }
    }

    /**
     * 本副本的长连接已经断开。心跳列保留，其它副本的行不改。
     *
     * @param instance 断开的 agent
     */
    @Override
    public void markDown(AgentInstance instance) {
        String instanceId = requireInstance(instance);
        ensure();
        String sql = "UPDATE jasagent_gateway_liveness SET connected = 0 WHERE replica_id = ? AND instance_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replicaId);
            statement.setString(2, instanceId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw persist("存活库写入失败");
        }
    }

    /**
     * 记下本副本这次往返的时间。新行先记成未连接，避免一次心跳把已经断开的副本又标活。
     * 已经连着的行只更新时间，不改连接标记。
     *
     * @param instance 仍连着的 agent
     * @param when 确认时刻，为空时忽略
     */
    @Override
    public void heartbeat(AgentInstance instance, Instant when) {
        if (when == null) {
            return;
        }
        String instanceId = requireInstance(instance);
        ensure();
        String sql = """
                INSERT INTO jasagent_gateway_liveness (replica_id, instance_id, connected, last_heartbeat_ms)
                VALUES (?, ?, 0, ?)
                ON DUPLICATE KEY UPDATE last_heartbeat_ms = VALUES(last_heartbeat_ms)
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replicaId);
            statement.setString(2, instanceId);
            statement.setLong(3, when.toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw persist("存活库写入失败");
        }
    }

    /**
     * 任一副本仍标记为连接、且心跳落在新鲜度内时为真。本副本断开不影响其它副本的近期心跳。
     *
     * @param instance 要查询的 agent
     * @return 还可以转发时为真
     */
    @Override
    public boolean connectionAlive(AgentInstance instance) {
        String instanceId = requireInstance(instance);
        ensure();
        String sql = """
                SELECT 1 FROM jasagent_gateway_liveness
                WHERE instance_id = ? AND connected = 1 AND last_heartbeat_ms >= ?
                LIMIT 1
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, instanceId);
            statement.setLong(2, cutoff());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException ex) {
            throw persist("存活库读取失败");
        }
    }

    /**
     * 各副本里最晚的一次心跳。包括已经断开的副本，这样集群页在下线后仍能显示上次时间。
     *
     * @param instance 要查询的 agent
     * @return 心跳时刻；没有时为空
     */
    @Override
    public Optional<Instant> lastHeartbeat(AgentInstance instance) {
        String instanceId = requireInstance(instance);
        ensure();
        String sql = "SELECT MAX(last_heartbeat_ms) FROM jasagent_gateway_liveness WHERE instance_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, instanceId);
            try (ResultSet rows = statement.executeQuery()) {
                return readInstant(rows);
            }
        } catch (SQLException ex) {
            throw persist("存活库读取失败");
        }
    }

    /**
     * 本副本自己写下的心跳。断开后仍保留，不代表当前还连着。
     *
     * @param instance 要查询的 agent
     * @return 本副本的心跳；没写过时为空
     */
    public Optional<Instant> replicaHeartbeat(AgentInstance instance) {
        String instanceId = requireInstance(instance);
        ensure();
        String sql = "SELECT last_heartbeat_ms FROM jasagent_gateway_liveness WHERE replica_id = ? AND instance_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replicaId);
            statement.setString(2, instanceId);
            try (ResultSet rows = statement.executeQuery()) {
                return readInstant(rows);
            }
        } catch (SQLException ex) {
            throw persist("存活库读取失败");
        }
    }

    /**
     * 本副本到这台 agent 的连接标记是否还在，并且心跳未过期。不看其它副本。
     *
     * @param instance 要查询的 agent
     * @return 本副本仍连着时为真
     */
    public boolean replicaConnected(AgentInstance instance) {
        String instanceId = requireInstance(instance);
        ensure();
        String sql = """
                SELECT 1 FROM jasagent_gateway_liveness
                WHERE replica_id = ? AND instance_id = ? AND connected = 1 AND last_heartbeat_ms >= ?
                LIMIT 1
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replicaId);
            statement.setString(2, instanceId);
            statement.setLong(3, cutoff());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException ex) {
            throw persist("存活库读取失败");
        }
    }

    /**
     * 删掉这台 agent 在所有副本上的测试行。生产路径不调用。
     *
     * @param instanceId 要清掉的实例编号；空白时什么也不做
     */
    public void deleteInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank() || instanceId.length() > 64) {
            return;
        }
        ensure();
        String sql = "DELETE FROM jasagent_gateway_liveness WHERE instance_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, instanceId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw persist("存活库写入失败");
        }
    }

    /**
     * 进程退出时把本副本的连接标记清掉。心跳时间留着，其它副本的行不动。
     * 库暂时连不上时忽略，过期心跳不会再被当成存活。不记录异常原文，避免口令混进日志。
     */
    @Override
    public void destroy() {
        try {
            markReplicaDown();
        } catch (RuntimeException ignored) {
            // 关闭阶段不再抛出。新鲜度窗口过后，残留的连接标记不再算存活。
        }
    }

    /**
     * 返回不含口令的说明。主机、端口、库名和副本编号取自实际使用的值。
     *
     * @return 只标明库位置和副本编号
     */
    @Override
    public String toString() {
        return "MysqlAgentLiveness{" + location(url) + ",replica=" + replicaId + "}";
    }

    /**
     * 把本副本的全部连接标记清成断开。不删除心跳，也不修改其它副本。
     */
    private void markReplicaDown() {
        ensure();
        String sql = "UPDATE jasagent_gateway_liveness SET connected = 0 WHERE replica_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replicaId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw persist("存活库写入失败");
        }
    }

    /**
     * 当前时刻减去新鲜度，更早的已连接心跳不再算存活。
     *
     * @return 截止的纪元毫秒
     */
    private long cutoff() {
        return now().minus(freshness).toEpochMilli();
    }

    /**
     * 读取时钟。时钟返回空时拒绝，不把空时刻写进库。
     *
     * @return 当前时刻
     */
    private Instant now() {
        Instant value = clock.get();
        if (value == null) {
            throw new AgentException(ErrorCode.INTERNAL, "存活时刻无效");
        }
        return value;
    }

    /**
     * 从结果里取出心跳毫秒。没有行或值为空时为空。
     *
     * @param rows 已经执行的查询
     * @return 心跳时刻
     * @throws SQLException 驱动读失败时抛出，由调用方改写成不含口令的异常
     */
    private static Optional<Instant> readInstant(ResultSet rows) throws SQLException {
        if (!rows.next() || rows.getObject(1) == null) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli(rows.getLong(1)));
    }

    /**
     * 确认副本编号能放进主键。空值或超长时拒绝，说明里不带回原文。
     *
     * @param replicaId 候选编号
     * @return 通过校验的编号
     */
    private static String requireReplica(String replicaId) {
        if (replicaId == null || !REPLICA_ID.matcher(replicaId).matches()) {
            throw new AgentException(ErrorCode.INTERNAL, "存活键无效");
        }
        return replicaId;
    }

    /**
     * 确认实例编号能放进主键列。编号最长 64，和粘性表一致。
     *
     * @param instance 要写入或查询的 agent
     * @return 实例编号
     */
    private static String requireInstance(AgentInstance instance) {
        if (instance == null || instance.instanceId() == null || instance.instanceId().isBlank() || instance.instanceId().length() > 64) {
            throw new AgentException(ErrorCode.INTERNAL, "agent 地址无效");
        }
        return instance.instanceId();
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
                throw persist("存活库连接失败");
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
     * 从 JDBC 地址里取出主机、端口和库名。去掉用户信息和查询串，避免口令混进文本。
     *
     * @param jdbcUrl 已经拒绝过 password= 的地址
     * @return host:port/database
     */
    private static String location(String jdbcUrl) {
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
     * 把数据库失败收成固定说明。不附带原始异常，原始说明里可能出现口令。
     *
     * @param action 失败时给调用方的固定说明
     * @return 持久化异常
     */
    private static AgentException persist(String action) {
        return new AgentException(ErrorCode.PERSIST, action);
    }
}
