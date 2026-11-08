package dev.jasagent.api.plugin;

import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;

import java.util.concurrent.CompletionStage;

public interface PluginTool {
    ToolSpec spec();

    CompletionStage<String> call(ToolCall call);
}
