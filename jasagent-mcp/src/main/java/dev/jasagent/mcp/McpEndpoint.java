package dev.jasagent.mcp;

import java.util.List;

public interface McpEndpoint {
    String server();

    List<String> listTools();

    String call(String tool, String arguments);
}
