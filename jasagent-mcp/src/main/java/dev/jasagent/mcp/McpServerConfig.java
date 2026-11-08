package dev.jasagent.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;

public record McpServerConfig(String serverId, McpTransport transport, Duration timeout) {
    public McpServerConfig {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("服务器标识不能为空");
        }
        if (transport == null) {
            throw new IllegalArgumentException("传输不能为空");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("超时必须大于 0");
        }
    }

    public static McpServerConfig http(String serverId, URI endpoint, Duration timeout) {
        return new McpServerConfig(serverId, new McpTransport.Http(endpoint, java.util.Map.of()), timeout);
    }

    public static McpServerConfig stdio(String serverId, List<String> command, Duration timeout) {
        return new McpServerConfig(serverId, new McpTransport.Stdio(command, java.util.Map.of()), timeout);
    }
}
