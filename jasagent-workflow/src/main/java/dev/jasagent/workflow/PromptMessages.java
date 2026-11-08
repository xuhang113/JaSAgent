package dev.jasagent.workflow;

import dev.jasagent.workflow.ChatModel.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PromptMessages {
    private PromptMessages() {
    }

    static List<ChatMessage> assemble(WorkflowState state) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", system(state)));
        String summary = StateValues.text(state, "summary");
        if (!summary.isBlank()) {
            messages.add(new ChatMessage("summary", summary));
        }
        for (Map<String, String> turn : StateValues.maps(state, "recent")) {
            messages.add(new ChatMessage(turn.getOrDefault("role", ""), turn.getOrDefault("content", "")));
        }
        for (Map<String, String> note : StateValues.maps(state, "corrections")) {
            messages.add(new ChatMessage("correction", note.getOrDefault("text", "")));
        }
        messages.add(new ChatMessage("user", StateValues.text(state, "input")));
        for (String toolText : StateValues.strings(state, "toolTexts")) {
            messages.add(new ChatMessage("tool", toolText));
        }
        return List.copyOf(messages);
    }

    private static String system(WorkflowState state) {
        StringBuilder text = new StringBuilder();
        text.append("role: ").append(StateValues.text(state, "role")).append('\n');
        text.append("rules:\n");
        for (String rule : StateValues.strings(state, "rules")) {
            text.append(rule).append('\n');
        }
        text.append("skills:\n");
        for (String skill : StateValues.strings(state, "skills")) {
            text.append(skill).append('\n');
        }
        text.append("tasks:\n");
        for (String task : StateValues.strings(state, "tasks")) {
            text.append(task).append('\n');
        }
        return text.toString();
    }
}
