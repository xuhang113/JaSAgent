package dev.jasagent.api.llm;

/**
 * 一次已经发生的模型调用账目，供事后查看走了哪个供应方、花了多少 token、是否发生过回退。
 *
 * @param executionId 调用所属的执行编号
 * @param task 任务的线上名称，例如 react、compress；嵌入调用记为 embed
 * @param provider 实际应答的供应方标识
 * @param model 实际使用的模型名
 * @param latencyMillis 从发起调用到拿到结果的毫秒数
 * @param promptTokens 提示词消耗的 token 数；嵌入调用记为 0
 * @param completionTokens 生成内容消耗的 token 数；嵌入调用记为 0
 * @param totalTokens 提示词与生成内容合计的 token 数；嵌入调用记为 0
 * @param fallback 为 true 时表示这次调用是主路径失败后的备用供应方
 */
public record LlmCall(
        String executionId,
        String task,
        String provider,
        String model,
        long latencyMillis,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean fallback) {
}
