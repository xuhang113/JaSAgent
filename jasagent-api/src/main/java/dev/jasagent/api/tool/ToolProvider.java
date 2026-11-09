package dev.jasagent.api.tool;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 工具来源。插件或外部连接用它把一批可调用工具交给目录。
 */
public interface ToolProvider {
    /**
     * 装入这个来源当前提供的工具。
     *
     * @return 完成后给出工具列表的阶段；列表中的每个工具都带有自己的 {@link ToolSpec}
     */
    CompletionStage<List<Tool>> tools();
}
