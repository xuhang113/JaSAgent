package dev.jasagent.core;

import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.workflow.CorrectionNote;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolMemory {
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|“([^”]+)”");

    public List<ChatMessage> reconcile(
            List<ChatMessage> history,
            String toolResult,
            String userMessage,
            List<CorrectionNote> notes,
            String sessionId,
            String executionId) {
        String assistant = "";
        for (int index = history.size() - 1; index >= 0; index--) {
            if ("assistant".equals(history.get(index).role())) {
                assistant = history.get(index).content();
                break;
            }
        }
        Matcher matcher = QUOTED.matcher(assistant);
        boolean conflict = false;
        while (matcher.find()) {
            String fragment = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (fragment != null && !toolResult.contains(fragment) && (userMessage == null || !userMessage.contains(fragment))) {
                conflict = true;
            }
        }
        if (conflict && toolResult != null && !toolResult.isBlank()) {
            notes.add(new CorrectionNote(UUID.randomUUID().toString(), sessionId, executionId, Instant.now(), "TOOL_CONTRADICTION", "以工具结果为准：" + toolResult));
        }
        List<ChatMessage> next = new ArrayList<>(history);
        next.add(ChatMessage.text("tool", toolResult));
        return next;
    }
}
