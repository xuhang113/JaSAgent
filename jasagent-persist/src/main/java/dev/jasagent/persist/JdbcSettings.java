package dev.jasagent.persist;

import java.nio.file.Path;
import java.util.Map;

public final class JdbcSettings {
    private final String url;
    private final String user;
    private final String password;
    private final boolean postgresql;

    private JdbcSettings(String url, String user, String password, boolean postgresql) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.postgresql = postgresql;
    }

    public static JdbcSettings resolve(Path workspace, Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        String configured = env.get("JASAGENT_JDBC_URL");
        String url = configured == null || configured.isBlank() ? defaultH2Url(workspace) : configured;
        boolean postgresql = url.startsWith("jdbc:postgresql:");
        String user = env.get("JASAGENT_JDBC_USER");
        if (user == null || user.isBlank()) {
            user = postgresql ? "" : "sa";
        }
        String password = env.get("JASAGENT_JDBC_PASSWORD");
        if (password == null) {
            password = "";
        }
        return new JdbcSettings(url, user, password, postgresql);
    }

    public static Path defaultH2File(Path workspace) {
        return workspace.toAbsolutePath().resolve("jasagent").resolve("data").resolve("jasagent");
    }

    public static String defaultH2Url(Path workspace) {
        return "jdbc:h2:file:" + defaultH2File(workspace) + ";DB_CLOSE_DELAY=-1";
    }

    public String url() {
        return url;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public boolean postgresql() {
        return postgresql;
    }

    @Override
    public String toString() {
        return "JdbcSettings[url=" + url + ", user=" + user + ", postgresql=" + postgresql + "]";
    }
}
