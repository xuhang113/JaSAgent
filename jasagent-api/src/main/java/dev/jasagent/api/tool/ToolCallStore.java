package dev.jasagent.api.tool;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 工具调用流水。目录在每次调用结束时追加一条，事后按执行编号回看参数摘要、通行证和输出。
 */
public interface ToolCallStore {
    /**
     * 追加一条工具调用流水。
     *
     * @param entry 执行编号、用户、工具名、参数摘要、通行证决定、是否执行和输出
     * @return 追加完成后结束的阶段；正常完成表示流水已经写下，没有业务返回值
     */
    CompletionStage<Void> append(ToolCallEntry entry);

    /**
     * 读出某次执行已经记下的全部工具调用。
     *
     * @param executionId 执行编号
     * @return 完成后给出该执行的流水列表的阶段
     */
    CompletionStage<List<ToolCallEntry>> find(String executionId);
}
