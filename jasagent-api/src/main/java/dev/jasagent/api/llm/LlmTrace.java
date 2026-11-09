package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

/**
 * 模型路由的追踪出口。路由在回退供应方、选择嵌入器时把一行说明写到对应执行上。
 */
public interface LlmTrace {
    /**
     * 为指定执行追加一行模型路由追踪。调用方应先去掉密钥等敏感内容。
     *
     * @param executionId 追踪所属的执行编号
     * @param line 已经脱敏的一行说明，例如回退到的供应方和模型
     * @return 写入完成后结束的阶段；正常完成表示这行已经记下，没有业务返回值
     */
    CompletionStage<Void> write(String executionId, String line);
}
