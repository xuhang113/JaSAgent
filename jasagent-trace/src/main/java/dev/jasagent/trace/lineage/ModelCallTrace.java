package dev.jasagent.trace.lineage;

/**
 * 一次模型调用在请求溯源里的位置。
 * 记录请求时 traceId 和 executionId 可以空着，写入后会改成和这次请求相同。
 * 按 executionId 查回来时，这两个字段已经填好。
 *
 * @param callId 这次模型调用的标识，同一次执行内不能重复
 * @param provider 供应方，例如 deepseek 或 ai-gateway
 * @param model 模型名
 * @param traceId 贯穿这次请求的追踪标识；记录时可以空
 * @param executionId 所属执行；记录时可以空
 */
public record ModelCallTrace(
        String callId,
        String provider,
        String model,
        String traceId,
        String executionId) {
}
