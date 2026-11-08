package dev.jasagent.persist;

import java.nio.file.Path;
import java.util.Map;

public record JdbcSettings(String url, String user, String password) {
    public static JdbcSettings from(Map<String, String> env, Map<String, String> properties, Path workspace) {
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        String url = blankToNull(safeEnv.get("JASAGENT_JDBC_URL"));
        if (url == null) {
            Path database = workspace.toAbsolutePath().normalize().resolve("jasagent").resolve("data").resolve("jasagent");
            url = "jdbc:h2:file:" + database + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        }
        String user = blankToNull(safeEnv.get("JASAGENT_JDBC_USER"));
        if (user == null) {
            user = "sa";
        }
        String password = safeEnv.get("JASAGENT_JDBC_PASSWORD");
        if (password == null) {
            password = "";
        }
        return new JdbcSettings(url, user, password);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
