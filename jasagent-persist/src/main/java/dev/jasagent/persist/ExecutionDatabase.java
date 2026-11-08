package dev.jasagent.persist;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public final class ExecutionDatabase implements AutoCloseable {
    private final JdbcSettings settings;
    private final DataSource dataSource;

    private ExecutionDatabase(JdbcSettings settings, DataSource dataSource) {
        this.settings = settings;
        this.dataSource = dataSource;
    }

    public static ExecutionDatabase open(Path workspace, Map<String, String> environment) {
        JdbcSettings settings = JdbcSettings.resolve(workspace, environment);
        if (!settings.postgresql()) {
            createH2Parent(settings.url());
        }
        DataSource dataSource = new DriverDataSource(settings.url(), settings.user(), settings.password());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:dev/jasagent/persist/db/migration")
                .load()
                .migrate();
        return new ExecutionDatabase(settings, dataSource);
    }

    public JdbcSettings settings() {
        return settings;
    }

    public Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
    }

    private static void createH2Parent(String url) {
        String path = url.substring("jdbc:h2:file:".length());
        int options = path.indexOf(';');
        if (options >= 0) {
            path = path.substring(0, options);
        }
        try {
            Files.createDirectories(Path.of(path).getParent());
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建执行库目录", ex);
        }
    }
}
