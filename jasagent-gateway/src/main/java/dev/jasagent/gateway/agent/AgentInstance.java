package dev.jasagent.gateway.agent;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 一台 agent。实例编号给集群和执行详情，地址是网关实际连接的主机和端口。
 *
 * @param instanceId 稳定编号，形如 agent-18731
 * @param host 网关实际连接的主机，来自 Nacos 登记的地址
 * @param port 网关实际连接的端口
 */
public record AgentInstance(String instanceId, String host, int port) {
    /**
     * 第一台 agent 的端口。长连接连到这个端口，测试不会占用它。
     */
    public static final int PRIMARY_PORT = 18731;
    /**
     * 第二台 agent 的端口。长连接连到这个端口，测试不会占用它。
     */
    public static final int SECONDARY_PORT = 18732;

    /**
     * 校验编号、主机和端口。空编号、空主机或端口超出 1 到 65535 时拒绝。
     *
     * @param instanceId 实例编号
     * @param host 主机
     * @param port 端口
     */
    public AgentInstance {
        if (instanceId == null || instanceId.isBlank() || host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new AgentException(ErrorCode.INTERNAL, "agent 地址无效");
        }
        instanceId = instanceId.trim();
        host = host.trim();
    }

    /**
     * 本机监听约定的第一台，编号 agent-18731，地址 127.0.0.1:18731。
     * 这只是本机端口约定，网关发现不得调用它，也不把它当作 Nacos 的查询结果。
     *
     * @return 本机约定的第一台
     */
    public static AgentInstance primary() {
        return new AgentInstance("agent-" + PRIMARY_PORT, "127.0.0.1", PRIMARY_PORT);
    }

    /**
     * 本机监听约定的第二台，编号 agent-18732，地址 127.0.0.1:18732。
     * 这只是本机端口约定，网关发现不得调用它。
     *
     * @return 本机约定的第二台
     */
    public static AgentInstance secondary() {
        return new AgentInstance("agent-" + SECONDARY_PORT, "127.0.0.1", SECONDARY_PORT);
    }

    /**
     * 按端口从小到大返回本机监听约定的两台 agent。
     * 网关发现不得调用它，配置空白时也不能把它当作查询结果。
     *
     * @return 不可变列表，先 18731 再 18732
     */
    public static List<AgentInstance> defaults() {
        return List.of(primary(), secondary());
    }

    /**
     * 浏览器和执行详情里显示的地址，形如 127.0.0.1:18731。
     *
     * @return 主机加端口
     */
    public String address() {
        return host + ":" + port;
    }

    /**
     * 把逗号分隔的 host:port 收成实例列表。空白时返回空列表，不再回退到本机约定的两台。
     * 网关发现不调用这个方法。编号取 agent-端口。重复端口或格式不对时拒绝。
     *
     * @param raw 文本，空白时不是发现结果
     * @return 按端口升序的实例；空白时为空列表
     */
    public static List<AgentInstance> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<AgentInstance> parsed = new ArrayList<>();
        for (String item : raw.split(",")) {
            String text = item.trim();
            if (text.isEmpty()) {
                continue;
            }
            int colon = text.lastIndexOf(':');
            if (colon <= 0 || colon == text.length() - 1) {
                throw new AgentException(ErrorCode.INTERNAL, "agent 地址无效");
            }
            String host = text.substring(0, colon).trim();
            int port;
            try {
                port = Integer.parseInt(text.substring(colon + 1).trim());
            } catch (NumberFormatException ex) {
                throw new AgentException(ErrorCode.INTERNAL, "agent 地址无效");
            }
            AgentInstance instance = new AgentInstance("agent-" + port, host, port);
            for (AgentInstance existing : parsed) {
                if (existing.port == instance.port) {
                    throw new AgentException(ErrorCode.INTERNAL, "agent 端口重复");
                }
            }
            parsed.add(instance);
        }
        if (parsed.isEmpty()) {
            throw new AgentException(ErrorCode.INTERNAL, "agent 地址无效");
        }
        parsed.sort((left, right) -> Integer.compare(left.port, right.port));
        return List.copyOf(parsed);
    }
}
