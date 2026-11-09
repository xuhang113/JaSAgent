package dev.jasagent.persist;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** 执行记录库。打开时按设置连上 H2 或外部库，并跑完 Flyway 迁移。 */
public final class ExecutionDatabase implements AutoCloseable {
    private final JdbcSettings settings;
    private final DataSource dataSource;

    /**
     * 记下连接设置和已经迁移过的数据源。
     *
     * @param settings 解析后的 JDBC 设置
     * @param dataSource 用来取连接的数据源
     */
    private ExecutionDatabase(JdbcSettings settings, DataSource dataSource) {
        this.settings = settings;
        this.dataSource = dataSource;
    }

    /**
     * 解析连接，H2 时先创建库文件的父目录，跑完迁移后返回可用的执行库。
     *
     * @param workspace 工作区，默认 H2 放在这里
     * @param environment 进程环境
     * @return 已完成迁移的执行库
     */
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

    /**
     * 返回打开时用的连接设置，包括地址、用户和口令。
     *
     * @return JDBC 设置
     */
    public JdbcSettings settings() {
        return settings;
    }

    /**
     * 从数据源取一条新连接，调用方负责关闭。
     *
     * @return 数据库连接
     * @throws SQLException 驱动拒绝连接时抛出
     */
    public Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    /** 关闭占位。数据源没有要在这里释放的连接池。 */
    @Override
    public void close() {
    }

    /**
     * 从 H2 文件地址里取出库文件，并创建它的父目录。创建失败时抛出非法状态异常。
     *
     * @param url 以 jdbc:h2:file: 开头的地址
     */
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
