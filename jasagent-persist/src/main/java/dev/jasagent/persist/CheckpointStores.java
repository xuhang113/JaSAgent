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

public final class CheckpointStores {
    static final String DEFAULT_MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/urag"
            + "?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED";

    private CheckpointStores() {
    }

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

    public static String workflowJdbc(Map<String, String> environment, Path sqliteFile) {
        CheckpointSelection selection = select(environment).withSqlite(sqliteFile);
        if (selection.backend() == CheckpointBackend.SQLITE) {
            return "jdbc:sqlite:" + sqliteFile.toAbsolutePath();
        }
        return selection.jdbcUrl();
    }

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

    private static CheckpointSelection mysqlFrom(String url, Map<String, String> env) {
        MysqlUrl parsed = MysqlUrl.parse(url);
        String user = first(parsed.user(), env.get("JASAGENT_CHECKPOINT_JDBC_USER"), env.get("JASAGENT_JDBC_USER"), "root");
        String password = first(parsed.password(), env.get("JASAGENT_CHECKPOINT_JDBC_PASSWORD"), env.get("JASAGENT_JDBC_PASSWORD"), readUragPassword());
        if (password == null || password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 检查点配置");
        }
        return new CheckpointSelection(CheckpointBackend.MYSQL, null, parsed.url(), user, password, parsed.host(), parsed.port(), parsed.database());
    }

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

    private static Path sqlitePath(String checkpointUrl, String jdbc) {
        String url = starts(checkpointUrl, "jdbc:sqlite:") ? checkpointUrl : (starts(jdbc, "jdbc:sqlite:") ? jdbc : "");
        if (url.isBlank()) {
            return null;
        }
        return Path.of(url.substring("jdbc:sqlite:".length()));
    }

    private static void loadDriver(CheckpointBackend backend) throws ClassNotFoundException {
        if (backend == CheckpointBackend.MYSQL) {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } else if (backend == CheckpointBackend.POSTGRES) {
            Class.forName("org.postgresql.Driver");
        }
    }

    private static void copy(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }

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

    private static boolean starts(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

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

    private record MysqlUrl(String url, String user, String password, String host, int port, String database) {
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

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }
}
