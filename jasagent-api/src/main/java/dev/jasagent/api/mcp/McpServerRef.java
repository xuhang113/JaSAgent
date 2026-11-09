package dev.jasagent.api.mcp;

/**
 * 一个要去连接的 MCP 服务器。
 * 服务器标识会拼进登记后的工具名，传输方式决定是拉起子进程还是向 HTTP 地址发请求。
 *
 * @param name 服务器标识
 * @param transport 传输方式名称，用来区分子进程和 HTTP
 */
public record McpServerRef(String name, String transport) {
}
