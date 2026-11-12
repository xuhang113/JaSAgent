package dev.jasagent.gateway.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取本机 MySQL 根口令，用来连接 jasagent 库。口令只来自用户主目录下的 {@code .config/urag-mysql-root.password}，不写进仓库，也不出现在日志里。
 */
public final class UragPassword {
    /**
     * 口令文件名。放在用户主目录的 .config 下，不在仓库里。
     */
    public static final String FILE_NAME = "urag-mysql-root.password";

    /**
     * 禁止实例化。口令只通过 {@link #read()} 获取。
     */
    private UragPassword() {
    }

    /**
     * 读取口令文件的第一行并去掉首尾空白。文件不存在或读失败时返回空串，不把文件内容放进异常。
     *
     * @return 口令；没有时是空串
     */
    public static String read() {
        Path file = Path.of(System.getProperty("user.home", ""), ".config", FILE_NAME);
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
}
