package dev.jasagent.api.llm;

/**
 * 一次对话补全的 token 用量，供调用记录和计费查看提示词与生成内容各花了多少。
 *
 * @param promptTokens 提示词消耗的 token 数
 * @param completionTokens 生成内容消耗的 token 数
 */
public record TokenUsage(int promptTokens, int completionTokens) {
}
