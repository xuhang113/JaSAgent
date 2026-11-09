package dev.jasagent.api.llm;

/**
 * 随对话请求交给模型的一个工具说明。客户端据此生成工具规格，模型只能调用这里列出的工具。
 *
 * @param name 工具名，空白名称会被客户端跳过
 * @param description 向模型说明何时使用该工具；缺失时按空串发送
 * @param parametersJson 参数的 JSON Schema 文本，含 properties 与 required；空白时按无参数发送
 */
public record ToolDefinition(String name, String description, String parametersJson) {
}
