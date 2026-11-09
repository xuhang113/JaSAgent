package dev.jasagent.api.mcp;

import dev.jasagent.api.tool.Tool;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 把 MCP 服务器上的远端工具登记成本地可调用的工具。
 * 单个服务器连不上时，其他服务器仍继续登记。
 */
public interface McpClient {
    /**
     * 连接这些服务器，列出远端工具并包装成可调用工具。
     * 握手和列工具完成之前，返回的阶段不会结束。
     *
     * @param servers 要连接的服务器
     * @return 完成后得到已登记工具列表的阶段
     */
    CompletionStage<List<Tool>> register(List<McpServerRef> servers);
}
