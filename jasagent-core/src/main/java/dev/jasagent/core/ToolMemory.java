package dev.jasagent.core;

import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.context.CorrectionSource;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolMemory {
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|\u201c([^\u201d]+)\u201d");

    public List<ChatMessage> reconcile(
            List<ChatMessage> history,
            String toolResult,
            String userMessage,
            List<CorrectionNote> notes,
            String sessionId,
            String executionId) {
        if (notes == null) {
            throw new AgentException(ErrorCode.INTERNAL, "notes");
        }
        String assistant = "";
        if (history != null) {
            for (int index = history.size() - 1; index >= 0; index--) {
                ChatMessage message = history.get(index);
                if (message != null && "assistant".equals(message.role())) {
                    assistant = message.content() == null ? "" : message.content();
                    break;
                }
            }
        }
        String tool = toolResult == null ? "" : toolResult;
        String user = userMessage == null ? "" : userMessage;
        if (!tool.isBlank() && conflicts(assistant, tool, user)) {
            notes.add(new CorrectionNote(
                    UUID.randomUUID().toString(),
                    sessionId,
                    executionId,
                    Instant.now(),
                    CorrectionSource.TOOL_CONTRADICTION,
                    "以工具结果为准：" + tool));
        }
        List<ChatMessage> next = new ArrayList<>();
        if (history != null) {
            next.addAll(history);
        }
        if (toolResult != null && !tool.isBlank()) {
            next.add(ChatMessage.text("tool", toolResult));
        }
        return List.copyOf(next);
    }

    private static boolean conflicts(String assistant, String tool, String user) {
        Matcher matcher = QUOTED.matcher(assistant);
        while (matcher.find()) {
            String fragment = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (fragment != null && !fragment.isBlank() && !tool.contains(fragment) && !user.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
