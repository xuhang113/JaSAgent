package dev.jasagent.api.llm;

import java.util.List;

/**
 * 送给上下文压缩、评测裁判或工作流的一条对话。除角色和正文外，还可以带上模型要求执行的工具调用。
 *
 * @param role 消息角色，例如 system、user、assistant、tool
 * @param content 消息正文
 * @param toolCalls 这条消息附带的工具调用；没有调用时为空列表
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls) {
    /**
     * 一条工具调用的轻量引用，只保存调用编号、工具名和参数 JSON。
     *
     * @param id 模型分配的调用编号
     * @param name 工具名
     * @param argumentsJson 参数 JSON 文本
     */
    public record ToolCallRef(String id, String name, String argumentsJson) {
    }

    /**
     * 复制工具调用列表。传入 null 时改成空列表，否则复制为不可变列表。
     *
     * @param role 消息角色
     * @param content 消息正文
     * @param toolCalls 附带的工具调用，null 会变成空列表
     */
    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * 构造一条没有工具调用的纯文本消息。
     *
     * @param role 消息角色
     * @param content 消息正文
     * @return 工具调用列表为空的消息
     */
    public static ChatMessage text(String role, String content) {
        return new ChatMessage(role, content, List.of());
    }
}
