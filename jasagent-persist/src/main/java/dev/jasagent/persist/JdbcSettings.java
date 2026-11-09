package dev.jasagent.persist;

import dev.jasagent.api.persist.DataDirectory;

import java.nio.file.Path;
import java.util.Map;

/** 执行记录库的 JDBC 连接。没配地址时用工作区里的 H2 文件库，用户默认 sa，口令为空。 */
public final class JdbcSettings {
    private final String url;
    private final String user;
    private final String password;
    private final boolean postgresql;

    /**
     * 记下解析后的地址、账号，以及这条地址是不是 PostgreSQL。
     *
     * @param url JDBC 地址
     * @param user 数据库用户
     * @param password 数据库口令，不应写进工作区文件
     * @param postgresql 地址是否以 jdbc:postgresql: 开头
     */
    private JdbcSettings(String url, String user, String password, boolean postgresql) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.postgresql = postgresql;
    }

    /**
     * 从环境变量解析执行库连接。JDBC 地址为空时落到工作区 H2。PostgreSQL 不填用户时用空串，其它库不填用户时用 sa；口令缺省为空串。
     *
     * @param workspace 工作区，用来安放默认 H2 文件
     * @param environment 进程环境，空时按没有配置处理
     * @return 可直接拿去建数据源的设置
     */
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

    /**
     * 默认 H2 文件在工作区数据目录下，文件名是 jasagent，不含数据库文件后缀。
     *
     * @param workspace 工作区根目录
     * @return H2 文件路径
     */
    public static Path defaultH2File(Path workspace) {
        return DataDirectory.of(workspace).resolve("jasagent");
    }

    /**
     * 拼出默认 H2 的 JDBC 地址，并让库在最后一个连接关闭后仍然保持打开。
     *
     * @param workspace 工作区根目录
     * @return 带关闭延迟的 jdbc:h2:file 地址
     */
    public static String defaultH2Url(Path workspace) {
        return "jdbc:h2:file:" + defaultH2File(workspace) + ";DB_CLOSE_DELAY=-1";
    }

    /**
     * 返回执行库 JDBC 地址。
     *
     * @return JDBC 地址
     */
    public String url() {
        return url;
    }

    /**
     * 返回执行库用户名。
     *
     * @return 用户名
     */
    public String user() {
        return user;
    }

    /**
     * 返回执行库口令。调用方只应把它交给驱动，不要写入工作区。
     *
     * @return 口令，未配置时为空串
     */
    public String password() {
        return password;
    }

    /**
     * 判断这条连接是不是 PostgreSQL，用来决定要不要建 H2 父目录以及默认用户。
     *
     * @return 地址以 jdbc:postgresql: 开头时为真
     */
    public boolean postgresql() {
        return postgresql;
    }

    /**
     * 拼出不含口令的设置摘要。
     *
     * @return 地址、用户，以及是否为 PostgreSQL
     */
    @Override
    public String toString() {
        return "JdbcSettings[url=" + url + ", user=" + user + ", postgresql=" + postgresql + "]";
    }
}
