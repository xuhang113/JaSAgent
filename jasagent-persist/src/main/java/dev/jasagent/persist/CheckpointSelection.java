package dev.jasagent.persist;

import java.nio.file.Path;

/** 一次打开检查点存储器所需的后端、JDBC 连接和账号。SQLite 只使用文件路径，连接字段留空。 */
public final class CheckpointSelection {
    private final CheckpointBackend backend;
    private final Path sqliteFile;
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String host;
    private final int port;
    private final String database;

    /**
     * 装入一次选择。空的地址、用户、口令、主机和库名收成空串，调用方不必再判断空引用。
     *
     * @param backend 后端种类
     * @param sqliteFile SQLite 库文件，其它后端为空
     * @param jdbcUrl 打开数据库的 JDBC 地址
     * @param user 数据库用户
     * @param password 数据库口令
     * @param host 数据库主机
     * @param port 数据库端口，SQLite 时为 0
     * @param database 库名
     */
    CheckpointSelection(
            CheckpointBackend backend,
            Path sqliteFile,
            String jdbcUrl,
            String user,
            String password,
            String host,
            int port,
            String database) {
        this.backend = backend;
        this.sqliteFile = sqliteFile;
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.host = host == null ? "" : host;
        this.port = port;
        this.database = database == null ? "" : database;
    }

    /**
     * 仅当后端是 SQLite 时换成另一个库文件，并清掉 JDBC 字段。其它后端保持当前选择不变。
     *
     * @param sqliteFile 要改用的 SQLite 文件
     * @return 换过文件的 SQLite 选择；后端不是 SQLite 时就是当前对象
     */
    public CheckpointSelection withSqlite(Path sqliteFile) {
        if (backend != CheckpointBackend.SQLITE) {
            return this;
        }
        return new CheckpointSelection(backend, sqliteFile, "", "", "", "", 0, "");
    }

    /**
     * 返回这次选择的数据库种类。
     *
     * @return SQLite、PostgreSQL 或 MySQL
     */
    public CheckpointBackend backend() {
        return backend;
    }

    /**
     * 返回 SQLite 库文件。其它后端没有这个文件。
     *
     * @return 库文件路径，或空
     */
    public Path sqliteFile() {
        return sqliteFile;
    }

    /**
     * 返回打开数据库用的 JDBC 地址。换成 SQLite 文件之后这里是空串。
     *
     * @return JDBC 地址，没有时为空串
     */
    public String jdbcUrl() {
        return jdbcUrl;
    }

    /**
     * 返回数据库用户名。没有配过时是空串。
     *
     * @return 用户名
     */
    public String user() {
        return user;
    }

    /**
     * 返回数据库口令。没有配过时是空串，打印选择时不应把这里的值露出去。
     *
     * @return 口令
     */
    public String password() {
        return password;
    }

    /**
     * 返回数据库主机。SQLite 选择这里是空串。
     *
     * @return 主机名或地址
     */
    public String host() {
        return host;
    }

    /**
     * 返回数据库端口。SQLite 选择这里是 0，PostgreSQL 没写端口时是 5432，MySQL 默认是 3306。
     *
     * @return 端口号
     */
    public int port() {
        return port;
    }

    /**
     * 返回库名。SQLite 选择这里是空串。
     *
     * @return 数据库名
     */
    public String database() {
        return database;
    }

    /**
     * 拼出给日志看的选择摘要，并把 JDBC 地址里的 password 参数换成星号。
     *
     * @return 后端、打码后的地址、用户和库名
     */
    @Override
    public String toString() {
        String shown = jdbcUrl.replaceAll("(?i)(password=)[^&]*", "$1***");
        return "CheckpointSelection[backend=" + backend + ", url=" + shown + ", user=" + user + ", database=" + database + "]";
    }
}
