package dev.jasagent.api.llm;

import java.util.List;

/**
 * 一次大模型对话补全的返回值，记下实际应答的供应方、模型、正文、token 用量，以及这次应答是否由备用供应方完成。
 *
 * @param provider 实际应答的供应方标识，例如 deepseek 或 ai-gateway
 * @param model 实际使用的模型名
 * @param text 模型给出的正文
 * @param promptTokens 提示词消耗的 token 数
 * @param completionTokens 生成内容消耗的 token 数
 * @param totalTokens 提示词与生成内容合计的 token 数
 * @param fallback 为 true 时表示主路径失败后改由备用供应方完成本次应答
 * @param thinking 模型附带的思考文本；没有思考时为空串
 * @param toolCalls 模型要求执行的工具调用；没有调用时为空列表
 */
public record LlmChatResponse(
        String provider,
        String model,
        String text,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean fallback,
        String thinking,
        List<LlmToolCall> toolCalls) {

    /**
     * 收齐一次补全结果。thinking 为 null 时改成空串；toolCalls 为 null 时改成空列表，否则复制为不可变列表。
     *
     * @param provider 实际应答的供应方标识
     * @param model 实际使用的模型名
     * @param text 模型给出的正文
     * @param promptTokens 提示词消耗的 token 数
     * @param completionTokens 生成内容消耗的 token 数
     * @param totalTokens 提示词与生成内容合计的 token 数
     * @param fallback 为 true 时表示本次应答来自备用供应方
     * @param thinking 模型附带的思考文本，null 会变成空串
     * @param toolCalls 模型要求执行的工具调用，null 会变成空列表
     */
    public LlmChatResponse {
        if (thinking == null) {
            thinking = "";
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * 用空的工具调用列表构造补全结果，思考文本按传入值保留。
     *
     * @param provider 实际应答的供应方标识
     * @param model 实际使用的模型名
     * @param text 模型给出的正文
     * @param promptTokens 提示词消耗的 token 数
     * @param completionTokens 生成内容消耗的 token 数
     * @param totalTokens 提示词与生成内容合计的 token 数
     * @param fallback 为 true 时表示本次应答来自备用供应方
     * @param thinking 模型附带的思考文本
     */
    public LlmChatResponse(
            String provider,
            String model,
            String text,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean fallback,
            String thinking) {
        this(provider, model, text, promptTokens, completionTokens, totalTokens, fallback, thinking, List.of());
    }

    /**
     * 用空的思考文本和空的工具调用列表构造补全结果。
     *
     * @param provider 实际应答的供应方标识
     * @param model 实际使用的模型名
     * @param text 模型给出的正文
     * @param promptTokens 提示词消耗的 token 数
     * @param completionTokens 生成内容消耗的 token 数
     * @param totalTokens 提示词与生成内容合计的 token 数
     * @param fallback 为 true 时表示本次应答来自备用供应方
     */
    public LlmChatResponse(
            String provider,
            String model,
            String text,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean fallback) {
        this(provider, model, text, promptTokens, completionTokens, totalTokens, fallback, "", List.of());
    }
}
