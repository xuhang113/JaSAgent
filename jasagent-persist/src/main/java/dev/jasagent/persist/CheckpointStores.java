package dev.jasagent.persist;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.CreateOption;
import org.bsc.langgraph4j.checkpoint.MySQLSaverV2;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.checkpoint.SQLiteSaverV2;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按环境变量和本机 MySQL 配置选出检查点后端，并打开对应的 langgraph4j 存储器。
 * 没写明数据库时先读 my.cnf，再读本机 urag 库口令，最后落到 MySQL。
 */
public final class CheckpointStores {
    /** 本机 urag 库的默认地址，指向 127.0.0.1:3306，连接超时短，并关闭 SSL。 */
    static final String DEFAULT_MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/urag"
            + "?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED";

    /** 不创建实例。选库和打开存储器都走静态方法。 */
    private CheckpointStores() {
    }

    /**
     * 根据检查点模式和两条 JDBC 地址决定用 SQLite、PostgreSQL 还是 MySQL。
     * 模式和地址都没指明数据库时，改为发现本机 MySQL。
     *
     * @param environment 进程环境，空时按没有配置处理
     * @return 选定的后端、连接和账号
     */
    public static CheckpointSelection select(Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        String mode = text(env.get("JASAGENT_CHECKPOINT"));
        String checkpointUrl = text(env.get("JASAGENT_CHECKPOINT_JDBC_URL"));
        String jdbc = text(env.get("JASAGENT_JDBC_URL"));
        if ("sqlite".equalsIgnoreCase(mode) || starts(checkpointUrl, "jdbc:sqlite:") || starts(jdbc, "jdbc:sqlite:")) {
            return new CheckpointSelection(CheckpointBackend.SQLITE, sqlitePath(checkpointUrl, jdbc), "", "", "", "", 0, "");
        }
        if ("postgres".equalsIgnoreCase(mode) || starts(checkpointUrl, "jdbc:postgresql:") || starts(jdbc, "jdbc:postgresql:")) {
            String url = starts(checkpointUrl, "jdbc:postgresql:") ? checkpointUrl : jdbc;
            return postgres(url, env);
        }
        if (starts(checkpointUrl, "jdbc:mysql:") || starts(jdbc, "jdbc:mysql:") || "mysql".equalsIgnoreCase(mode)) {
            String url = starts(checkpointUrl, "jdbc:mysql:") ? checkpointUrl : (starts(jdbc, "jdbc:mysql:") ? jdbc : "");
            if (url.isBlank()) {
                return discover(env);
            }
            return mysqlFrom(url, env);
        }
        return discover(env);
    }

    /**
     * 给出工作流该用的 JDBC 地址。后端是 SQLite 时用传入文件的绝对路径，其它后端用选择结果里的地址。
     *
     * @param environment 进程环境
     * @param sqliteFile SQLite 库文件；只有后端是 SQLite 时才写进返回的地址
     * @return 工作流应使用的 JDBC 地址
     */
    public static String workflowJdbc(Map<String, String> environment, Path sqliteFile) {
        CheckpointSelection selection = select(environment).withSqlite(sqliteFile);
        if (selection.backend() == CheckpointBackend.SQLITE) {
            return "jdbc:sqlite:" + sqliteFile.toAbsolutePath();
        }
        return selection.jdbcUrl();
    }

    /**
     * 提前打开一次非 SQLite 检查点存储器，确认驱动、账号和建表脚本可用。SQLite 不连库，直接返回。打不开时抛出持久化异常。
     *
     * @param environment 进程环境，用来选择后端和账号
     */
    public static void prepare(Map<String, String> environment) {
        try {
            CheckpointSelection selection = select(environment);
            if (selection.backend() == CheckpointBackend.SQLITE) {
                return;
            }
            open(selection, new org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer<>(AgentState::new));
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点存储器无法打开", null, ex);
        }
    }

    /**
     * 探测当前选择的检查点库能否执行查询。SQLite 不连库，直接视为可达；其它后端执行 SELECT 1，任何异常都算不可达。
     *
     * @param environment 进程环境
     * @return 能连上并完成查询时为真
     */
    public static boolean reachable(Map<String, String> environment) {
        try {
            CheckpointSelection selection = select(environment);
            if (selection.backend() == CheckpointBackend.SQLITE) {
                return true;
            }
            loadDriver(selection.backend());
            try (Connection connection = DriverManager.getConnection(selection.jdbcUrl(), selection.user(), selection.password());
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                return true;
            }
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 按调用方给出的 JDBC 地址打开存储器。地址为空时改读进程环境，并套上传入的 SQLite 文件。
     * 认不出 sqlite、postgresql、mysql 三种协议时抛出持久化异常。
     *
     * @param <S> 图状态类型，必须是 AgentState 的子类
     * @param sqliteFile 地址为空或选择结果是 SQLite 时使用的库文件
     * @param jdbcUrl 显式 JDBC 地址，可以是 sqlite、postgresql 或 mysql
     * @param serializer 图状态序列化器
     * @return 已按需建表的检查点存储器
     * @throws Exception 地址协议不认识，或打开存储器失败
     */
    public static <S extends AgentState> BaseCheckpointSaver openRequested(
            Path sqliteFile,
            String jdbcUrl,
            StateSerializer<S> serializer) throws Exception {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return open(select(new HashMap<>(System.getenv())).withSqlite(sqliteFile), serializer);
        }
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            Path file = Path.of(jdbcUrl.substring("jdbc:sqlite:".length()));
            return open(select(Map.of("JASAGENT_CHECKPOINT", "sqlite")).withSqlite(file), serializer);
        }
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            Map<String, String> env = new HashMap<>();
            env.put("JASAGENT_CHECKPOINT", "postgres");
            env.put("JASAGENT_JDBC_URL", jdbcUrl);
            return open(select(env), serializer);
        }
        if (jdbcUrl.startsWith("jdbc:mysql:")) {
            Map<String, String> env = new HashMap<>();
            env.put("JASAGENT_CHECKPOINT", "mysql");
            env.put("JASAGENT_CHECKPOINT_JDBC_URL", jdbcUrl);
            copy(env, "JASAGENT_CHECKPOINT_JDBC_USER");
            copy(env, "JASAGENT_CHECKPOINT_JDBC_PASSWORD");
            copy(env, "JASAGENT_JDBC_USER");
            copy(env, "JASAGENT_JDBC_PASSWORD");
            return open(select(env), serializer);
        }
        throw new AgentException(ErrorCode.PERSIST, "不认识的检查点连接");
    }

    /**
     * 按已经解析好的选择打开存储器。SQLite 会先建父目录；PostgreSQL 带两秒超时；MySQL 用驱动数据源并在表不存在时创建。
     * 同一时刻只允许一个线程改 Saver 的 SQL 覆盖层。
     *
     * @param selection 后端、连接和账号
     * @param serializer 图状态序列化器
     * @return 对应后端的检查点存储器
     * @throws Exception SQLite 文件缺失，或驱动、建表失败
     */
    public static BaseCheckpointSaver open(CheckpointSelection selection, StateSerializer<? extends AgentState> serializer) throws Exception {
        synchronized (SaverResources.LOCK) {
            if (selection.backend() == CheckpointBackend.SQLITE) {
                if (selection.sqliteFile() == null) {
                    throw new AgentException(ErrorCode.PERSIST, "检查点存储器无法打开");
                }
                Path parent = selection.sqliteFile().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                SaverResources.use(SQLiteSaverV2.class);
                return SQLiteSaverV2.builder()
                        .databasePath(selection.sqliteFile().toString())
                        .stateSerializer(serializer)
                        .createTables(true)
                        .build();
            }
            if (selection.backend() == CheckpointBackend.POSTGRES) {
                SaverResources.use(PostgresSaverV2.class);
                return PostgresSaverV2.builder()
                        .host(selection.host())
                        .port(selection.port())
                        .database(selection.database())
                        .user(selection.user())
                        .password(selection.password())
                        .property("connectTimeout", "2")
                        .property("loginTimeout", "2")
                        .property("socketTimeout", "2")
                        .stateSerializer(serializer)
                        .createTables(true)
                        .build();
            }
            loadDriver(CheckpointBackend.MYSQL);
            SaverResources.use(MySQLSaverV2.class);
            return MySQLSaverV2.builder()
                    .dataSource(new DriverDataSource(selection.jdbcUrl(), selection.user(), selection.password()))
                    .stateSerializer(serializer)
                    .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                    .build();
        }
    }

    /**
     * 环境没写明数据库时，先读 my.cnf 的客户端段；没有可用口令再读本机 urag 根口令，并连到默认的 urag 库。两处都没有口令时抛出持久化异常。
     *
     * @param env 进程环境，用来补用户名
     * @return MySQL 检查点选择
     */
    private static CheckpointSelection discover(Map<String, String> env) {
        CheckpointSelection file = fromMyCnf();
        if (file != null) {
            return file;
        }
        String password = readUragPassword();
        if (password == null || password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 检查点配置");
        }
        String user = first(env.get("JASAGENT_CHECKPOINT_JDBC_USER"), "root");
        return new CheckpointSelection(CheckpointBackend.MYSQL, null, DEFAULT_MYSQL_URL, user, password, "127.0.0.1", 3306, "urag");
    }

    /**
     * 从一条 MySQL JDBC 地址里拆出主机、端口、库名和账号。口令不够时依次用检查点环境变量、通用 JDBC 环境变量和本机 urag 口令补上。
     *
     * @param url 以 jdbc:mysql: 开头的地址
     * @param env 进程环境
     * @return MySQL 检查点选择
     */
    private static CheckpointSelection mysqlFrom(String url, Map<String, String> env) {
        MysqlUrl parsed = MysqlUrl.parse(url);
        String user = first(parsed.user(), env.get("JASAGENT_CHECKPOINT_JDBC_USER"), env.get("JASAGENT_JDBC_USER"), "root");
        String password = first(parsed.password(), env.get("JASAGENT_CHECKPOINT_JDBC_PASSWORD"), env.get("JASAGENT_JDBC_PASSWORD"), readUragPassword());
        if (password == null || password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 检查点配置");
        }
        return new CheckpointSelection(CheckpointBackend.MYSQL, null, parsed.url(), user, password, parsed.host(), parsed.port(), parsed.database());
    }

    /**
     * 从 PostgreSQL JDBC 地址拆出主机、端口、库名，以及查询参数里的账号。没写端口时用 5432。主机或库名为空时抛出持久化异常。
     *
     * @param jdbcUrl 以 jdbc:postgresql: 开头的地址
     * @param env 进程环境，用来补查询参数里没有的用户和口令
     * @return PostgreSQL 检查点选择
     */
    private static CheckpointSelection postgres(String jdbcUrl, Map<String, String> env) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "PostgreSQL JDBC URL 缺少主机或数据库");
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String database = uri.getPath() == null ? "" : uri.getPath();
        if (database.startsWith("/")) {
            database = database.substring(1);
        }
        int query = database.indexOf('?');
        if (query >= 0) {
            database = database.substring(0, query);
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || database.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "PostgreSQL JDBC URL 缺少主机或数据库");
        }
        Map<String, String> params = query(uri.getRawQuery());
        String user = first(params.get("user"), env.get("JASAGENT_JDBC_USER"), "");
        String password = params.containsKey("password") ? params.get("password") : text(env.get("JASAGENT_JDBC_PASSWORD"));
        int port = uri.getPort() < 0 ? 5432 : uri.getPort();
        return new CheckpointSelection(CheckpointBackend.POSTGRES, null, jdbcUrl, user, password, uri.getHost(), port, database);
    }

    /**
     * 依次读用户主目录和常见安装路径上的 my.cnf，取 client 或 mysql 段里同时写了口令和库名的那一处。
     *
     * @return 能拼出完整连接的 MySQL 选择；文件都不含口令和库名时为空
     */
    private static CheckpointSelection fromMyCnf() {
        String home = System.getProperty("user.home", "");
        List<Path> files = List.of(
                Path.of(home, ".my.cnf"),
                Path.of("/opt/homebrew/etc/my.cnf"),
                Path.of("/usr/local/etc/my.cnf"),
                Path.of("/etc/my.cnf"));
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Map<String, String> client = readClient(file);
            String password = client.get("password");
            String database = client.get("database");
            if (password == null || password.isBlank() || database == null || database.isBlank()) {
                continue;
            }
            String host = first(client.get("host"), "127.0.0.1");
            int port = parsePort(client.get("port"), 3306);
            String user = first(client.get("user"), "root");
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED";
            return new CheckpointSelection(CheckpointBackend.MYSQL, null, url, user, password, host, port, database);
        }
        return null;
    }

    /**
     * 读取一个配置文件里 client 或 mysql 段的键值，跳过注释和其它段。读失败时返回空映射。
     *
     * @param file my.cnf 路径
     * @return 小写键到去掉外层引号后的值
     */
    private static Map<String, String> readClient(Path file) {
        Map<String, String> values = new HashMap<>();
        boolean client = false;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    String name = line.substring(1, line.length() - 1).trim();
                    client = "client".equalsIgnoreCase(name) || "mysql".equalsIgnoreCase(name);
                    continue;
                }
                if (!client || !line.contains("=")) {
                    continue;
                }
                int split = line.indexOf('=');
                String key = line.substring(0, split).trim().toLowerCase();
                String value = unquote(line.substring(split + 1).trim());
                values.put(key, value);
            }
        } catch (Exception ex) {
            return Map.of();
        }
        return values;
    }

    /**
     * 读取用户主目录下 urag MySQL 根口令文件的第一行。
     *
     * @return 去掉首尾空白后的口令；文件不存在或读失败时为空串
     */
    private static String readUragPassword() {
        Path file = Path.of(System.getProperty("user.home", ""), ".config", "urag-mysql-root.password");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            String line = Files.readString(file, StandardCharsets.UTF_8);
            int newline = line.indexOf('\n');
            if (newline >= 0) {
                line = line.substring(0, newline);
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            return line.trim();
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 从检查点地址或通用 JDBC 地址里取出 jdbc:sqlite: 后面的文件路径。检查点地址优先。
     *
     * @param checkpointUrl 检查点专用 JDBC 地址
     * @param jdbc 通用 JDBC 地址
     * @return SQLite 文件；两个地址都不是 sqlite 时为空
     */
    private static Path sqlitePath(String checkpointUrl, String jdbc) {
        String url = starts(checkpointUrl, "jdbc:sqlite:") ? checkpointUrl : (starts(jdbc, "jdbc:sqlite:") ? jdbc : "");
        if (url.isBlank()) {
            return null;
        }
        return Path.of(url.substring("jdbc:sqlite:".length()));
    }

    /**
     * 装载 MySQL 或 PostgreSQL 的 JDBC 驱动类，让后面的 DriverManager 能按地址建连。SQLite 不在这里装。
     *
     * @param backend 要连接的后端
     * @throws ClassNotFoundException 驱动不在类路径上
     */
    private static void loadDriver(CheckpointBackend backend) throws ClassNotFoundException {
        if (backend == CheckpointBackend.MYSQL) {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } else if (backend == CheckpointBackend.POSTGRES) {
            Class.forName("org.postgresql.Driver");
        }
    }

    /**
     * 把当前进程环境里非空的同名变量抄进目标映射，供后面的选择读取。空值不覆盖。
     *
     * @param env 正在组装的环境
     * @param key 变量名
     */
    private static void copy(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }

    /**
     * 把 JDBC 查询串拆成键值并做 URL 解码。没有等号的片段跳过。
     *
     * @param raw 问号后面的原始查询串，可以为空
     * @return 参数名到参数值
     */
    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                continue;
            }
            values.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    /**
     * 把端口文本转成整数。空串或不是整数时用给定默认端口。
     *
     * @param value 端口文本
     * @param fallback 解析失败时返回的端口
     * @return 解析出的端口或默认端口
     */
    private static int parsePort(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * 去掉成对的单引号或双引号。引号不成对时保持原文，避免把口令里的引号误删。
     *
     * @param value 配置文件里的原始值
     * @return 去掉外层引号后的文本
     */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * 判断文本是否以给定前缀开头。空文本视为不匹配，避免环境变量缺失时被当成某种 JDBC。
     *
     * @param value 待测文本
     * @param prefix 前缀，例如 jdbc:mysql:
     * @return 非空且以前缀开头时为真
     */
    private static boolean starts(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    /**
     * 去掉首尾空白。空引用收成空串。
     *
     * @param value 原始文本
     * @return 修剪后的文本，不会是空引用
     */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 按顺序返回第一个非空白字符串。候选本身为空或全是空白时返回空串。
     *
     * @param values 按优先级排列的候选
     * @return 第一个有内容的候选，否则为空串
     */
    private static String first(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 从一条 MySQL JDBC 地址里拆出的连接片段。用户信息里的账号会从地址中剥掉，避免口令留在后续使用的 URL 里。
     *
     * @param url 去掉用户信息之后的 JDBC 地址
     * @param user 地址用户信息里的用户名，没有时为空串
     * @param password 地址用户信息里的口令，没有时为空串
     * @param host 主机，地址没写时是 127.0.0.1
     * @param port 端口，地址没写时是 3306
     * @param database 库名，地址没写路径时为空串
     */
    private record MysqlUrl(String url, String user, String password, String host, int port, String database) {
        /**
         * 解析 MySQL JDBC 地址，抽出用户信息、主机、端口和库名。
         *
         * @param raw 原始 jdbc:mysql 地址
         * @return 拆开后的连接片段
         */
        static MysqlUrl parse(String raw) {
            String url = raw;
            String user = "";
            String password = "";
            int scheme = url.indexOf("://");
            if (scheme >= 0) {
                int slash = url.indexOf('/', scheme + 3);
                int at = url.indexOf('@', scheme + 3);
                if (at > scheme && (slash < 0 || at < slash)) {
                    String userInfo = url.substring(scheme + 3, at);
                    url = url.substring(0, scheme + 3) + url.substring(at + 1);
                    int colon = userInfo.indexOf(':');
                    if (colon < 0) {
                        user = decode(userInfo);
                    } else {
                        user = decode(userInfo.substring(0, colon));
                        password = decode(userInfo.substring(colon + 1));
                    }
                }
            }
            String host = "127.0.0.1";
            int port = 3306;
            String database = "";
            int schemeEnd = url.indexOf("://");
            if (schemeEnd >= 0) {
                String rest = url.substring(schemeEnd + 3);
                int path = rest.indexOf('/');
                String hostPort = path < 0 ? rest : rest.substring(0, path);
                int query = hostPort.indexOf('?');
                if (query >= 0) {
                    hostPort = hostPort.substring(0, query);
                }
                int colon = hostPort.lastIndexOf(':');
                if (colon > 0 && hostPort.indexOf(':') == colon) {
                    host = hostPort.substring(0, colon);
                    port = parsePort(hostPort.substring(colon + 1), 3306);
                } else if (!hostPort.isBlank()) {
                    host = hostPort;
                }
                if (path >= 0) {
                    database = rest.substring(path + 1);
                    int databaseQuery = database.indexOf('?');
                    if (databaseQuery >= 0) {
                        database = database.substring(0, databaseQuery);
                    }
                }
            }
            return new MysqlUrl(url, user, password, host, port, database);
        }

        /**
         * 按 UTF-8 解码地址里百分号转义过的用户名或口令。
         *
         * @param value 用户信息中的一段
         * @return 解码后的文本
         */
        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }
}
