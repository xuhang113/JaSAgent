package dev.jasagent.tool;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolCatalog {
    private final Map<String, Tool> tools;

    public ToolCatalog(List<Tool> tools) {
        Map<String, Tool> mapped = new LinkedHashMap<>();
        for (Tool tool : tools) {
            String name = tool.spec().name();
            if (mapped.put(name, tool) != null) {
                throw new AgentException(ErrorCode.TOOL, "同名工具 " + name);
            }
        }
        this.tools = Map.copyOf(mapped);
    }

    public Tool require(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new AgentException(ErrorCode.TOOL, "没有工具 " + name);
        }
        return tool;
    }
}
