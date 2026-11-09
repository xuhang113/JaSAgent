package dev.jasagent.api.llm;

/**
 * 附在对话消息上的一次工具调用，供上下文和后续轮次知道模型调用了哪个工具、参数是什么。
 *
 * @param id 模型分配的调用编号
 * @param name 工具名
 * @param argumentsJson 参数 JSON 文本
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
