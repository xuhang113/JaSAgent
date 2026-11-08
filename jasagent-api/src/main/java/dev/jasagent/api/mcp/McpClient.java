package dev.jasagent.api.mcp;

import dev.jasagent.api.tool.Tool;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface McpClient {
    CompletionStage<List<Tool>> register(List<McpServerRef> servers);
}
