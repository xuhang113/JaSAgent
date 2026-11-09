package dev.jasagent.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 工作流向模型要一次回复的入口。请求里带执行编号、任务、消息和可用工具。
 */
public interface ChatModel {
    /**
     * 按请求生成一次模型回复，回复可以同时含正文、工具调用和思考。
     *
     * @param request 执行编号、任务名、消息和工具名
     * @return 完成后给出模型回复的阶段
     */
    CompletionStage<ModelReply> complete(ModelRequest request);

    /**
     * 一次模型调用要带上的执行编号、任务、消息和工具名。
     *
     * @param executionId 这次工作流执行的编号
     * @param task 模型任务名，例如 react
     * @param messages 按固定顺序排好的消息
     * @param tools 这次允许模型使用的工具名
     */
    record ModelRequest(String executionId, String task, List<ChatMessage> messages, List<String> tools) {
    }

    /**
     * 发给模型的一条消息。
     *
     * @param role 消息角色，例如 system、user、tool
     * @param content 消息正文
     */
    record ChatMessage(String role, String content) {
    }

    /**
     * 模型一次回复的正文、要求执行的工具和思考文本。
     *
     * @param content 交给用户或下一步的正文
     * @param toolCalls 模型要求执行的工具调用
     * @param thinking 模型的思考文本；没有时为空串
     */
    record ModelReply(String content, List<ToolCall> toolCalls, String thinking) {
        /**
         * 建一条没有思考文本的回复，思考收成空串。
         *
         * @param content 回复正文
         * @param toolCalls 工具调用；没有时传空列表
         */
        public ModelReply(String content, List<ToolCall> toolCalls) {
            this(content, toolCalls, "");
        }

        /**
         * 思考文本缺失时收成空串，避免后续读取时遇到空引用。
         *
         * @param content 回复正文，这里原样保留
         * @param toolCalls 工具调用，这里原样保留
         * @param thinking 思考文本；null 时改成空串
         */
        public ModelReply {
            if (thinking == null) {
                thinking = "";
            }
        }
    }

    /**
     * 模型要求执行的一次工具调用。
     *
     * @param id 调用编号
     * @param name 工具名
     * @param arguments 参数文本
     */
    record ToolCall(String id, String name, String arguments) {
    }
}
