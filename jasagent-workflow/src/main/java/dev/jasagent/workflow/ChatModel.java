package dev.jasagent.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ChatModel {
    CompletionStage<ModelReply> complete(ModelRequest request);

    record ModelRequest(String executionId, String task, List<ChatMessage> messages, List<String> tools) {
    }

    record ChatMessage(String role, String content) {
    }

    record ModelReply(String content, List<ToolCall> toolCalls, String thinking) {
        public ModelReply(String content, List<ToolCall> toolCalls) {
            this(content, toolCalls, "");
        }

        public ModelReply {
            if (thinking == null) {
                thinking = "";
            }
        }
    }

    record ToolCall(String id, String name, String arguments) {
    }
}
