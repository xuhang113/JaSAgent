package dev.jasagent.persist;

/**
 * 一次模型调用落库时的字段。
 *
 * @param callId 调用编号
 * @param executionId 所属执行编号
 * @param provider 模型供应商
 * @param modelName 模型名
 * @param latencyMs 调用耗时，单位毫秒
 * @param promptTokens 提示词 token 数
 * @param completionTokens 补全 token 数
 * @param totalTokens 提示词与补全合计 token 数
 */
public record LlmCallRow(
        String callId,
        String executionId,
        String provider,
        String modelName,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        int totalTokens) {
}
