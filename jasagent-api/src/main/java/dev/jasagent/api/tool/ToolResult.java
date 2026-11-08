package dev.jasagent.api.tool;

import dev.jasagent.api.pass.Pass;

public record ToolResult(String executionId, String name, boolean executed, String output, Pass pass) {
    public ToolResult(String text, boolean success) {
        this(null, null, success, text, null);
    }

    public String text() {
        return output;
    }

    public boolean success() {
        return executed;
    }
}
