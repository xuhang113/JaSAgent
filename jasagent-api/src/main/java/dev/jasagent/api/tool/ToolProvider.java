package dev.jasagent.api.tool;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ToolProvider {
    CompletionStage<List<Tool>> tools();
}
