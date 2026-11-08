package dev.jasagent.api.tool;

import dev.jasagent.api.pass.Pass;

public record ToolResult(String executionId, String name, boolean executed, String output, Pass pass) {
}
