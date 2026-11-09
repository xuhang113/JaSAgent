package dev.jasagent.mcp;

import dev.jasagent.api.tool.Tool;

import java.util.List;

/**
 * 提供一批已经可以调用的工具。
 * MCP 工具提供者实现它，把远程工具交回给调用方。
 */
public interface ToolProvider {
    /**
     * 返回当前可用的工具。
     *
     * @return 工具列表；没有连上任何工具时为空
     */
    List<Tool> tools();
}
