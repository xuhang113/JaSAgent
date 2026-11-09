package dev.jasagent.workflow;

import java.util.concurrent.CompletionStage;

/**
 * 按执行编号调用一个工具，并返回它的输出文本。
 */
public interface ToolCaller {
    /**
     * 执行一次工具调用。
     *
     * @param executionId 这次工作流执行的编号
     * @param name 工具名
     * @param arguments 参数文本
     * @return 完成后给出工具输出的阶段
     */
    CompletionStage<String> call(String executionId, String name, String arguments);
}
