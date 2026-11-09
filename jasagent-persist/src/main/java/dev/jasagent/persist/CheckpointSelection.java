package dev.jasagent.persist;

import java.nio.file.Path;

public final class CheckpointSelection {
    private final CheckpointBackend backend;
    private final Path sqliteFile;
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String host;
    private final int port;
    private final String database;

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

    public CheckpointSelection withSqlite(Path sqliteFile) {
        if (backend != CheckpointBackend.SQLITE) {
            return this;
        }
        return new CheckpointSelection(backend, sqliteFile, "", "", "", "", 0, "");
    }

    public CheckpointBackend backend() {
        return backend;
    }

    public Path sqliteFile() {
        return sqliteFile;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    @Override
    public String toString() {
        String shown = jdbcUrl.replaceAll("(?i)(password=)[^&]*", "$1***");
        return "CheckpointSelection[backend=" + backend + ", url=" + shown + ", user=" + user + ", database=" + database + "]";
    }
}
