package dev.jasagent.persist;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本机 jasagent 库的连接。口令只从用户主目录的配置文件读取，不进入日志、追踪或异常文本。
 * {@link #toString()} 只保留地址和用户名。
 */
public final class UragMysql {
    /**
     * 口令文件相对用户主目录的位置。文件内容的第一行是口令，读取后不得打印。
     */
    public static final String PASSWORD_FILE = ".config/urag-mysql-root.password";
    private final String url;
    private final String user;
    private final String password;

    /**
     * 记下地址、用户和口令。口令只交给 JDBC 驱动。
     *
     * @param url JDBC 地址，不含口令
     * @param user 数据库用户
     * @param password 数据库口令
     */
    private UragMysql(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /**
     * 口令文件有内容时返回连到 127.0.0.1:3306/jasagent 的配置，用户是 root。没有口令时返回 null。
     *
     * @return 可用的本机库；读不到口令时为 null
     */
    public static UragMysql discover() {
        String password = readPassword();
        if (password.isBlank()) {
            return null;
        }
        return new UragMysql(CheckpointStores.DEFAULT_MYSQL_URL, "root", password);
    }

    /**
     * 读取口令文件的第一行。文件不存在或读失败时返回空串，不把失败原因里的路径内容写进异常消息。
     *
     * @return 去掉首尾空白后的口令；没有时是空串
     */
    public static String readPassword() {
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
     * 返回不含口令的 JDBC 地址。
     *
     * @return JDBC 地址
     */
    public String url() {
        return url;
    }

    /**
     * 返回数据库用户名。
     *
     * @return 用户名
     */
    public String user() {
        return user;
    }

    /**
     * 返回口令。调用方只能把它交给驱动。
     *
     * @return 口令
     */
    public String password() {
        return password;
    }

    /**
     * 返回不含口令的摘要，避免被日志框架打出来。
     *
     * @return 地址和用户名
     */
    @Override
    public String toString() {
        return "UragMysql[url=" + url + ", user=" + user + "]";
    }
}
