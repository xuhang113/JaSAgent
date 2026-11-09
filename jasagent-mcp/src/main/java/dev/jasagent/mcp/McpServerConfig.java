package dev.jasagent.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * 一个 MCP 服务器的标识、传输方式和单次等待时间。
 *
 * @param serverId 服务器标识，会加进注册后的工具名
 * @param transport HTTP 或标准输入输出传输
 * @param timeout 单次请求的最长等待
 */
public record McpServerConfig(String serverId, McpTransport transport, Duration timeout) {
    /**
     * 拒绝空白标识、缺失的传输和非正的超时。
     * 副作用：三个组件都合法时保持原值；任一不合法就抛出非法参数异常，对象不会建出来。
     *
     * @param serverId 服务器标识，不能为空白
     * @param transport 传输方式，不能为空
     * @param timeout 超时，必须大于 0
     */
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

    /**
     * 做成不附加额外请求头的 HTTP 服务器配置。
     *
     * @param serverId 服务器标识
     * @param endpoint JSON-RPC 地址
     * @param timeout 单次请求的最长等待
     * @return HTTP 传输的服务器配置
     */
    public static McpServerConfig http(String serverId, URI endpoint, Duration timeout) {
        return new McpServerConfig(serverId, new McpTransport.Http(endpoint, java.util.Map.of()), timeout);
    }

    /**
     * 做成不附加额外环境变量的子进程服务器配置。
     *
     * @param serverId 服务器标识
     * @param command 启动命令
     * @param timeout 单次请求的最长等待
     * @return 标准输入输出传输的服务器配置
     */
    public static McpServerConfig stdio(String serverId, List<String> command, Duration timeout) {
        return new McpServerConfig(serverId, new McpTransport.Stdio(command, java.util.Map.of()), timeout);
    }
}
