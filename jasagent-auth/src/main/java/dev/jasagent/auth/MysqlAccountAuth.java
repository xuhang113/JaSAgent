package dev.jasagent.auth;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.AuthService;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.auth.UserAccount;
import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
import java.util.concurrent.CompletionStage;

/**
 * 把账号和登录会话写进已有 MySQL。口令只存 PBKDF2-HMAC-SHA256 摘要，登录令牌只存 SHA-256。
 * 文件目录只在迁入运维账号时读取一次，之后登录不再依赖那份文件或 emptyDir。
 * JDBC 地址不能带 password=。异常和 {@link #toString()} 不放口令或令牌。
 */
public final class MysqlAccountAuth implements AuthService, AccountDirectory {
    /**
     * 与网关粘性库相同的本机 jasagent 地址。地址里没有口令。
     */
    public static final String DEFAULT_URL = "jdbc:mysql://127.0.0.1:3306/jasagent"
            + "?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED";
    /**
     * 账号表。一行一个登录名，同时独占一个租户。
     */
    static final String ACCOUNT_TABLE = "jasagent_account";
    /**
     * 登录会话表。令牌列存的是哈希，不是 Cookie 原文。
     */
    static final String SESSION_TABLE = "jasagent_login_session";
    /**
     * 用户已存在时的固定说明。网关用它区分 409，说明里没有口令。
     */
    public static final String ALREADY_EXISTS = "用户已存在";
    /**
     * 建账号表。口令列只放摘要。
     */
    private static final String ACCOUNT_SCHEMA = """
            CREATE TABLE IF NOT EXISTS jasagent_account (
              login VARCHAR(64) NOT NULL,
              password_hash VARCHAR(512) NOT NULL,
              role VARCHAR(16) NOT NULL,
              tenant_id VARCHAR(64) NOT NULL,
              created_at VARCHAR(64) NOT NULL,
              PRIMARY KEY (login),
              UNIQUE KEY uk_jasagent_account_tenant (tenant_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
    /**
     * 建登录会话表。token_hash 唯一，用来按 Cookie 找回会话。
     */
    private static final String SESSION_SCHEMA = """
            CREATE TABLE IF NOT EXISTS jasagent_login_session (
              session_id VARCHAR(64) NOT NULL,
              token_hash CHAR(64) NOT NULL,
              login VARCHAR(64) NOT NULL,
              workspace VARCHAR(1024) NULL,
              created_at VARCHAR(64) NOT NULL,
              PRIMARY KEY (session_id),
              UNIQUE KEY uk_jasagent_login_token (token_hash),
              KEY idx_jasagent_login_session_login (login)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private final String url;
    private final String user;
    private final String password;
    private final LocalDirectories directories;
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
     * 记下连接和用来检查工作区的目录布局。地址里如果带了 password= 就拒绝。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param password 数据库口令，只交给驱动
     * @param directories 工作区校验用的目录布局，不作为账号的存储
     */
    private MysqlAccountAuth(String url, String user, String password, LocalDirectories directories) {
        if (url == null || url.isBlank() || url.toLowerCase().contains("password=")) {
            throw new AgentException(ErrorCode.PERSIST, "JDBC 地址不能携带口令");
        }
        if (password == null || password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
        }
        this.url = url;
        this.user = user == null ? "" : user;
        this.password = password;
        this.directories = directories;
    }

    /**
     * 打开账号库并建表。口令为空或地址带 password= 时失败，说明里没有口令。
     *
     * @param url JDBC 地址，不能包含口令
     * @param user 数据库账号
     * @param password 数据库口令
     * @return 已建表的账号服务
     */
    public static MysqlAccountAuth open(String url, String user, String password) {
        MysqlAccountAuth auth = new MysqlAccountAuth(url, user, password, LocalDirectories.processHome());
        auth.ensure();
        return auth;
    }

    /**
     * 实际使用的 JDBC 地址。配置了别的库时就是那个地址。
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
     * 把家目录里尚未入库的运维账号迁入，角色为 admin，并让调用方把旧的 local 会话改到新租户。
     * 已经在库里的登录名不会改角色，因此自助注册的普通用户不会被文件目录提权。
     * 副作用：插入账号和登录会话，并回调会话归属迁移。
     *
     * @param home 运维账号文件所在的家目录根
     * @param move 把该用户在 {@link AccountRoles#LEGACY_TENANT} 下的聊天会话改到新租户；为空时只迁账号
     */
    public void importOperators(Path home, SessionMove move) {
        List<FileAccounts.User> users;
        try {
            users = FileAccounts.read(home);
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "运维账号无法迁入");
        }
        ensure();
        for (FileAccounts.User operator : users) {
            if (accountExists(operator.login())) {
                continue;
            }
            String tenant = newTenantId();
            insertAccount(operator.login(), operator.passwordHash(), AccountRoles.ADMIN, tenant);
            insertFileSessions(operator);
            if (move != null) {
                move.apply(operator.login(), AccountRoles.LEGACY_TENANT, tenant);
            }
        }
    }

    /**
     * 自助注册。每次都新建租户，角色固定是普通用户，不读取调用方传来的租户。
     *
     * @param login 登录名
     * @param password 口令
     * @return 完成后得到新账号
     */
    @Override
    public CompletionStage<AccountProfile> register(String login, char[] password) {
        return VirtualIo.supply(() -> registerSync(login, password));
    }

    /**
     * 按登录名读取角色和租户。
     *
     * @param login 登录名
     * @return 账号身份
     */
    @Override
    public AccountProfile profile(String login) {
        String valid = LoginNames.require(login);
        ensure();
        String sql = "SELECT role, tenant_id FROM " + ACCOUNT_TABLE + " WHERE login = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, valid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AgentException(ErrorCode.AUTH, "会话无效");
                }
                return new AccountProfile(valid, rows.getString(1), rows.getString(2));
            }
        } catch (AgentException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw persist("账号无法读取");
        }
    }

    /**
     * 列出全部租户 id。
     *
     * @return 租户 id
     */
    @Override
    public List<String> tenantIds() {
        ensure();
        String sql = "SELECT tenant_id FROM " + ACCOUNT_TABLE + " ORDER BY login";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            List<String> found = new ArrayList<>();
            while (rows.next()) {
                String tenant = rows.getString(1);
                if (tenant != null && !tenant.isBlank()) {
                    found.add(tenant);
                }
            }
            return found;
        } catch (SQLException ex) {
            throw persist("账号无法读取");
        }
    }

    /**
     * 库里的口令是不是 PBKDF2 摘要，且摘要文本里没有口令原文。供测试核对存储，不返回摘要本身。
     *
     * @param login 登录名
     * @param plaintext 用来比对的口令原文，只参与校验，不写入响应
     * @return 摘要格式正确、不含原文、且能校验通过时为真
     */
    public boolean passwordIsHashed(String login, String plaintext) {
        String stored = findHash(login);
        if (stored == null || plaintext == null || stored.contains(plaintext)) {
            return false;
        }
        return stored.startsWith("pbkdf2-sha256$") && PasswordHashes.verify(plaintext.toCharArray(), stored);
    }

    /**
     * 删掉测试账号和它的登录会话。生产注册接口不调用。聊天会话不在这张表里。
     *
     * @param login 要删的登录名
     */
    public void deleteAccount(String login) {
        if (login == null || login.isBlank()) {
            return;
        }
        ensure();
        try (Connection connection = connect()) {
            try (PreparedStatement sessions = connection.prepareStatement("DELETE FROM " + SESSION_TABLE + " WHERE login = ?")) {
                sessions.setString(1, login);
                sessions.executeUpdate();
            }
            try (PreparedStatement accounts = connection.prepareStatement("DELETE FROM " + ACCOUNT_TABLE + " WHERE login = ?")) {
                accounts.setString(1, login);
                accounts.executeUpdate();
            }
        } catch (SQLException ex) {
            throw persist("账号无法删除");
        }
    }

    /**
     * 本机命令建账号。已有账号时必须带有效登录会话。新账号是 admin，供运维命令使用，不给自助注册走。
     *
     * @param request 登录名、口令和是否来自本机命令
     * @return 完成后得到登录名；目录路径只是接口要求的位置，口令不写进该目录
     */
    @Override
    public CompletionStage<UserAccount> addUser(AddUser request) {
        return VirtualIo.supply(() -> addUserSync(request));
    }

    /**
     * 核对摘要并签发登录会话。会话行里只有令牌哈希。
     *
     * @param login 登录名
     * @param password 口令
     * @return 新会话
     */
    @Override
    public CompletionStage<Session> login(String login, char[] password) {
        return VirtualIo.supply(() -> loginSync(login, password));
    }

    /**
     * 按令牌哈希删除登录会话。
     *
     * @param token 要作废的令牌
     * @return 删除完成后的阶段
     */
    @Override
    public CompletionStage<Void> logout(String token) {
        return VirtualIo.supply(() -> {
            logoutSync(token);
            return null;
        });
    }

    /**
     * 按令牌哈希找回会话。找不到时失败，不记录令牌。
     *
     * @param token 调用方持有的令牌
     * @return 会话
     */
    @Override
    public CompletionStage<Session> authenticate(String token) {
        return VirtualIo.supply(() -> findSession(token).session(token));
    }

    /**
     * 把已登录会话绑定到允许的工作区，并写回 MySQL。
     *
     * @param token 登录令牌
     * @param workspace 要绑定的工作区
     * @param allowlist 允许的工作区
     * @param currentWorkingDirectory 当前目录
     * @return 已写入工作区的会话
     */
    @Override
    public CompletionStage<Session> selectWorkspace(String token, Path workspace, List<Path> allowlist, Path currentWorkingDirectory) {
        return VirtualIo.supply(() -> selectWorkspaceSync(token, workspace, allowlist, currentWorkingDirectory));
    }

    /**
     * 不含口令的说明。只保留主机、端口和库名。
     *
     * @return 账号库位置
     */
    @Override
    public String toString() {
        return "MysqlAccountAuth{" + location(url) + "}";
    }

    /**
     * 同步注册普通用户。登录名和口令不合法、或登录名已存在时失败。
     *
     * @param login 登录名
     * @param password 口令
     * @return 新账号
     */
    private AccountProfile registerSync(String login, char[] password) {
        String valid = LoginNames.require(login);
        if (password == null || password.length == 0) {
            throw new AgentException(ErrorCode.AUTH, "口令不能为空");
        }
        String tenant = newTenantId();
        insertAccount(valid, PasswordHashes.hash(password), AccountRoles.USER, tenant);
        return new AccountProfile(valid, AccountRoles.USER, tenant);
    }

    /**
     * 同步按本机命令规则创建 admin。口令只写成摘要。
     *
     * @param request 建账请求
     * @return 新建账号。目录是接口上的用户目录，口令文件不写在那里
     */
    private UserAccount addUserSync(AddUser request) {
        String login = LoginNames.require(request.login());
        if (request.password() == null || request.password().length == 0) {
            throw new AgentException(ErrorCode.AUTH, "口令不能为空");
        }
        if (countAccounts() > 0) {
            if (!request.localCli()) {
                throw new AgentException(ErrorCode.AUTH, "只能由已登录的本机命令创建用户");
            }
            findSession(request.callerToken());
        }
        if (accountExists(login)) {
            throw new AgentException(ErrorCode.AUTH, ALREADY_EXISTS);
        }
        insertAccount(login, PasswordHashes.hash(request.password()), AccountRoles.ADMIN, newTenantId());
        return new UserAccount(login, directories.userDirectory(login));
    }

    /**
     * 同步登录。用户不存在和口令不对都用同一句说明，避免露出账号是否存在以外的材料。口令本身不进入说明。
     *
     * @param login 登录名
     * @param password 口令
     * @return 新会话
     */
    private Session loginSync(String login, char[] password) {
        String valid = LoginNames.require(login);
        String hash = findHash(valid);
        if (hash == null || password == null || !PasswordHashes.verify(password, hash)) {
            throw new AgentException(ErrorCode.AUTH, "登录失败");
        }
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        String sessionId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        insertSession(sessionId, tokenHash(token), valid, null, createdAt);
        return new Session(sessionId, token, valid, null, createdAt);
    }

    /**
     * 同步注销。令牌对不上时失败。
     *
     * @param token 登录令牌
     */
    private void logoutSync(String token) {
        ensure();
        String sql = "DELETE FROM " + SESSION_TABLE + " WHERE token_hash = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash(token));
            if (statement.executeUpdate() == 0) {
                throw new AgentException(ErrorCode.AUTH, "会话无效");
            }
        } catch (AgentException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw persist("会话无法注销");
        }
    }

    /**
     * 同步改写工作区。工作区先按原来的允许列表检查，再写入登录会话行。
     *
     * @param token 登录令牌
     * @param workspace 候选工作区
     * @param allowlist 允许列表
     * @param currentWorkingDirectory 当前目录
     * @return 更新后的会话
     */
    private Session selectWorkspaceSync(String token, Path workspace, List<Path> allowlist, Path currentWorkingDirectory) {
        StoredSession stored = findSession(token);
        Path selected = directories.checkSync(workspace, allowlist, currentWorkingDirectory);
        ensure();
        String sql = "UPDATE " + SESSION_TABLE + " SET workspace = ? WHERE session_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, selected.toString());
            statement.setString(2, stored.sessionId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw persist("会话无法写入");
        }
        return new Session(stored.sessionId, token, stored.login, selected.toString(), stored.createdAt);
    }

    /**
     * 插入账号。主键或租户冲突时视为用户已存在，说明里没有口令。
     *
     * @param login 登录名
     * @param passwordHash PBKDF2 摘要
     * @param role 角色
     * @param tenantId 租户
     */
    private void insertAccount(String login, String passwordHash, String role, String tenantId) {
        ensure();
        String sql = "INSERT INTO " + ACCOUNT_TABLE + " (login, password_hash, role, tenant_id, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, login);
            statement.setString(2, passwordHash);
            statement.setString(3, role);
            statement.setString(4, tenantId);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1062) {
                throw new AgentException(ErrorCode.AUTH, ALREADY_EXISTS);
            }
            throw persist("账号无法写入");
        }
    }

    /**
     * 把文件里的登录会话哈希后写入。坏行跳过，不把令牌写进异常。
     *
     * @param operator 已插入账号的运维用户
     */
    private void insertFileSessions(FileAccounts.User operator) {
        for (FileAccounts.SessionFile session : operator.sessions()) {
            try {
                insertSession(session.sessionId(), tokenHash(session.token()), operator.login(), session.workspace(), session.createdAt());
            } catch (AgentException ex) {
                if (!ALREADY_EXISTS.equals(ex.getMessage())) {
                    throw ex;
                }
            }
        }
    }

    /**
     * 插入一条登录会话。令牌列只接受已经算好的哈希。
     *
     * @param sessionId 会话编号
     * @param hashedToken 令牌的 SHA-256 十六进制
     * @param login 登录名
     * @param workspace 工作区；没有时为空
     * @param createdAt 创建时间
     */
    private void insertSession(String sessionId, String hashedToken, String login, String workspace, Instant createdAt) {
        ensure();
        String sql = "INSERT INTO " + SESSION_TABLE + " (session_id, token_hash, login, workspace, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, hashedToken);
            statement.setString(3, login);
            if (workspace == null || workspace.isBlank()) {
                statement.setString(4, null);
            } else {
                statement.setString(4, workspace);
            }
            statement.setString(5, createdAt.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1062) {
                throw new AgentException(ErrorCode.AUTH, ALREADY_EXISTS);
            }
            throw persist("会话无法写入");
        }
    }

    /**
     * 按令牌哈希读取会话行。
     *
     * @param token 原始令牌
     * @return 行里的身份；找不到时失败
     */
    private StoredSession findSession(String token) {
        ensure();
        String sql = "SELECT session_id, login, workspace, created_at FROM " + SESSION_TABLE + " WHERE token_hash = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash(token));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AgentException(ErrorCode.AUTH, "会话无效");
                }
                String workspace = rows.getString(3);
                return new StoredSession(rows.getString(1), rows.getString(2), workspace == null || workspace.isBlank() ? null : workspace, Instant.parse(rows.getString(4)));
            }
        } catch (AgentException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw persist("会话无法读取");
        }
    }

    /**
     * 读取口令摘要。没有这个登录名时为空，不说明原因里带登录材料以外的内容。
     *
     * @param login 已校验的登录名
     * @return 摘要；没有账号时为空
     */
    private String findHash(String login) {
        ensure();
        String sql = "SELECT password_hash FROM " + ACCOUNT_TABLE + " WHERE login = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, login);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return rows.getString(1);
            }
        } catch (SQLException ex) {
            throw persist("账号无法读取");
        }
    }

    /**
     * 登录名是否已经占用。
     *
     * @param login 登录名
     * @return 已有行时为真
     */
    private boolean accountExists(String login) {
        return findHash(login) != null;
    }

    /**
     * 账号行数，用来区分第一个运维账号和后续本机命令。
     *
     * @return 行数
     */
    private int countAccounts() {
        ensure();
        String sql = "SELECT COUNT(*) FROM " + ACCOUNT_TABLE;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException ex) {
            throw persist("账号无法读取");
        }
    }

    /**
     * 生成尚未占用的租户 id。格式是 t 加 32 位十六进制，长度适合粘性表的 64 字符上限。
     *
     * @return 新租户 id
     */
    private String newTenantId() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String tenant = "t" + UUID.randomUUID().toString().replace("-", "");
            if (!tenantTaken(tenant)) {
                return tenant;
            }
        }
        throw new AgentException(ErrorCode.INTERNAL, "租户无法分配");
    }

    /**
     * 租户 id 是否已经有人使用。
     *
     * @param tenantId 候选租户
     * @return 已占用时为真
     */
    private boolean tenantTaken(String tenantId) {
        ensure();
        String sql = "SELECT login FROM " + ACCOUNT_TABLE + " WHERE tenant_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException ex) {
            throw persist("账号无法读取");
        }
    }

    /**
     * 把登录令牌收成 SHA-256 十六进制。空白令牌直接视为无效，不把原文放进异常。
     *
     * @param token Cookie 或调用方持有的令牌
     * @return 64 位十六进制
     */
    static String tokenHash(String token) {
        if (token == null || token.isBlank()) {
            throw new AgentException(ErrorCode.AUTH, "会话无效");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "会话无法签发");
        }
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
                statement.execute(ACCOUNT_SCHEMA);
                statement.execute(SESSION_SCHEMA);
            } catch (SQLException ex) {
                throw persist("账号库连接失败");
            }
            ready = true;
        }
    }

    /**
     * 打开一条短连接。口令只作为驱动参数，不拼进地址。
     *
     * @return 新连接
     * @throws SQLException 驱动拒绝时抛出，由调用方改写
     */
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * 把数据库失败收成固定说明。不附带原始异常，避免口令混进文本。
     *
     * @param action 固定说明
     * @return 持久化异常
     */
    private static AgentException persist(String action) {
        return new AgentException(ErrorCode.PERSIST, action);
    }

    /**
     * 从 JDBC 地址里取出主机、端口和库名，去掉查询串和用户信息。
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
     * 迁聊天会话归属的回调。账号库不直接写 agent_session，避免另起一份会话账本。
     */
    @FunctionalInterface
    public interface SessionMove {
        /**
         * 把这个登录名在旧租户下的聊天会话改到他自己的租户。
         *
         * @param login 登录名
         * @param fromTenant 迁出的租户
         * @param toTenant 该用户自己的租户
         */
        void apply(String login, String fromTenant, String toTenant);
    }

    /**
     * 从登录会话表读出的一行。令牌不在行里，由调用方继续持有。
     */
    private static final class StoredSession {
        private final String sessionId;
        private final String login;
        private final String workspace;
        private final Instant createdAt;

        /**
         * 记下会话身份。
         *
         * @param sessionId 会话编号
         * @param login 登录名
         * @param workspace 工作区，可以是空
         * @param createdAt 创建时间
         */
        private StoredSession(String sessionId, String login, String workspace, Instant createdAt) {
            this.sessionId = sessionId;
            this.login = login;
            this.workspace = workspace;
            this.createdAt = createdAt;
        }

        /**
         * 组装对外的会话，把调用方仍持有的令牌放回去。
         *
         * @param token 原始令牌
         * @return 会话
         */
        private Session session(String token) {
            return new Session(sessionId, token, login, workspace, createdAt);
        }
    }
}
