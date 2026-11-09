package dev.jasagent.api.llm;

/**
 * 模型在一次补全里要求执行的一个工具调用，编号用于把后续工具结果对回这次调用。
 *
 * @param id 模型分配的调用编号；缺失时为空串
 * @param name 要执行的工具名；缺失时为空串
 * @param arguments 参数 JSON 文本；缺失时为空串
 */
public record LlmToolCall(String id, String name, String arguments) {
    /**
     * 把缺失的编号、工具名和参数 JSON 收成空串，避免调用方再判 null。
     *
     * @param id 模型分配的调用编号，null 会变成空串
     * @param name 要执行的工具名，null 会变成空串
     * @param arguments 参数 JSON 文本，null 会变成空串
     */
    public LlmToolCall {
        if (id == null) {
            id = "";
        }
        if (name == null) {
            name = "";
        }
        if (arguments == null) {
            arguments = "";
        }
    }
}
