package dev.jasagent.agentpass;

import dev.jasagent.agentpass.durable.AgentPassLedger;
import dev.jasagent.agentpass.durable.DurablePassGate;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.PassGate;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * 按配置决定通行证是关闭、按用户目录里的策略裁决，还是按 020 的 MySQL 裁决。
 * 只有明确写成 mysql 时才打开账本。缺省仍走目录策略，避免没有库的测试被本机口令文件带偏。
 */
public final class PassSettings {
    /**
     * 通行证开关的配置键；缺省视为开启，只有明确 false 才关闭。
     */
    public static final String ENABLED = "jasagent.agentpass.enabled";
    /**
     * 持久化来源。值为 mysql 时策略、待批和发证核对走已有库，不再读 policies.yaml。
     */
    public static final String DURABLE = "jasagent.agentpass.durable";
    /**
     * 通行证账本的 JDBC 地址。不能包含 password=。
     */
    public static final String JDBC_URL = "jasagent.agentpass.jdbc-url";
    /**
     * 通行证账本的数据库账号。空白时用 root。
     */
    public static final String JDBC_USER = "jasagent.agentpass.jdbc-user";
    /**
     * 通行证账本的数据库口令。只交给驱动，不写进日志。
     */
    public static final String JDBC_PASSWORD = "jasagent.agentpass.jdbc-password";

    /**
     * 禁止实例化，开关和门的选择都通过静态方法完成。
     */
    private PassSettings() {
    }

    /**
     * 读取通行证是否开启。缺省、空白和 true 都算开启；其他非 false 的值视为配置错误。
     *
     * @param configuration 运行配置；空则开启
     * @return 应当启用策略门时为真
     */
    public static boolean enabled(Map<String, String> configuration) {
        if (configuration == null || !configuration.containsKey(ENABLED)) {
            return true;
        }
        String value = configuration.get(ENABLED);
        if (value == null || value.isBlank() || "true".equals(value.trim())) {
            return true;
        }
        if ("false".equals(value.trim())) {
            return false;
        }
        throw new AgentException(ErrorCode.INTERNAL, ENABLED);
    }

    /**
     * 按开关创建门。关闭时不检查策略。开启且持久化来源是 mysql 时按租户账本裁决，否则仍读用户目录里的策略。
     *
     * @param configuration 含通行证开关和可选 JDBC 的配置
     * @param userDirectories 由用户标识找到其 jasagent 目录；mysql 模式下不读取
     * @return 关闭时的跳过门，账本门，或按文件策略裁决的门
     */
    public static PassGate create(Map<String, String> configuration, Function<String, Path> userDirectories) {
        if (!enabled(configuration)) {
            return new DisabledPassGate();
        }
        if (durable(configuration)) {
            return new DurablePassGate(AgentPassLedger.open(jdbcUrl(configuration), jdbcUser(configuration), jdbcPassword(configuration)));
        }
        return new PolicyPassGate(userDirectories);
    }

    /**
     * 判断这次配置是不是要求把策略和待批放到已有 MySQL。
     *
     * @param configuration 运行配置
     * @return 来源明确写成 mysql 时为真
     */
    private static boolean durable(Map<String, String> configuration) {
        if (configuration == null) {
            return false;
        }
        String value = configuration.get(DURABLE);
        return value != null && "mysql".equalsIgnoreCase(value.trim());
    }

    /**
     * 读取 JDBC 地址。空白或带着 password= 时拒绝，异常里不放地址。
     *
     * @param configuration 运行配置
     * @return 不含口令的 JDBC 地址
     */
    private static String jdbcUrl(Map<String, String> configuration) {
        String url = configuration.get(JDBC_URL);
        if (url == null || url.isBlank() || url.toLowerCase().contains("password=")) {
            throw new AgentException(ErrorCode.PERSIST, "JDBC 地址不能携带口令");
        }
        return url;
    }

    /**
     * 读取数据库账号。空白时用 root。
     *
     * @param configuration 运行配置
     * @return 数据库账号
     */
    private static String jdbcUser(Map<String, String> configuration) {
        String user = configuration.get(JDBC_USER);
        if (user == null || user.isBlank()) {
            return "root";
        }
        return user;
    }

    /**
     * 读取数据库口令。没有口令时拒绝，异常里不放口令。
     *
     * @param configuration 运行配置
     * @return 数据库口令
     */
    private static String jdbcPassword(Map<String, String> configuration) {
        String password = configuration.get(JDBC_PASSWORD);
        if (password == null || password.isBlank()) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
        }
        return password;
    }
}
