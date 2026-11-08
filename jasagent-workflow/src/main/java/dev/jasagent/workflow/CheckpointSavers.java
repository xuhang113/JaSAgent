package dev.jasagent.workflow;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.checkpoint.SQLiteSaverV2;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class CheckpointSavers {
    private CheckpointSavers() {
    }

    public static boolean postgres(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
    }

    public static <S extends AgentState> BaseCheckpointSaver open(
            Path sqliteFile,
            String jdbcUrl,
            StateSerializer<S> serializer) throws Exception {
        if (postgres(jdbcUrl)) {
            PostgresTarget target = parse(jdbcUrl);
            return PostgresSaverV2.builder()
                    .host(target.host())
                    .port(target.port())
                    .user(target.user())
                    .password(target.password())
                    .database(target.database())
                    .property("connectTimeout", "5")
                    .stateSerializer(serializer)
                    .createTables(true)
                    .build();
        }
        return SQLiteSaverV2.builder()
                .databasePath(sqliteFile.toString())
                .stateSerializer(serializer)
                .createTables(true)
                .build();
    }

    static PostgresTarget parse(String jdbcUrl) {
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String database = uri.getPath() == null ? "" : uri.getPath();
        if (database.startsWith("/")) {
            database = database.substring(1);
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || database.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL JDBC URL 缺少主机或数据库");
        }
        Map<String, String> query = query(uri.getRawQuery());
        int port = uri.getPort() < 0 ? 5432 : uri.getPort();
        return new PostgresTarget(
                uri.getHost(),
                port,
                query.getOrDefault("user", "jasagent"),
                query.getOrDefault("password", ""),
                database);
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

    record PostgresTarget(String host, int port, String user, String password, String database) {
    }
}
