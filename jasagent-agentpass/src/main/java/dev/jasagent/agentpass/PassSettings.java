package dev.jasagent.agentpass;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.PassGate;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * 按配置决定通行证是关闭，还是按用户策略裁决。
 */
public final class PassSettings {
    /**
     * 通行证开关的配置键；缺省视为开启，只有明确 false 才关闭。
     */
    public static final String ENABLED = "jasagent.agentpass.enabled";

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
     * 按开关创建门：关闭时不检查策略，开启时按用户目录里的策略裁决。
     *
     * @param configuration 含通行证开关的配置
     * @param userDirectories 由用户标识找到其 jasagent 目录
     * @return 关闭时的跳过门，或按策略裁决的门
     */
    public static PassGate create(Map<String, String> configuration, Function<String, Path> userDirectories) {
        if (!enabled(configuration)) {
            return new DisabledPassGate();
        }
        return new PolicyPassGate(userDirectories);
    }
}
