package dev.jasagent.workflow;

import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.workflow.CorrectionNote;
import dev.jasagent.api.workflow.WorkflowRun;

import java.util.ArrayList;
import java.util.List;

public final class PromptMessages {
    private PromptMessages() {
    }

    public static List<ChatMessage> assemble(WorkflowRun run, List<ChatMessage> recentTurns, List<CorrectionNote> notes) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.text("system", """
                角色：执行代理
                规则：%s
                技能：%s
                未完成任务：%s
                executionId：%s
                """.formatted(nullToEmpty(run.rulesText()), nullToEmpty(run.skillsText()), nullToEmpty(run.taskBoard()), run.executionId())));
        if (run.summary() != null && !run.summary().isBlank()) {
            messages.add(ChatMessage.text("system", run.summary()));
        }
        if (recentTurns != null) {
            messages.addAll(recentTurns);
        }
        if (notes != null) {
            for (CorrectionNote note : notes) {
                messages.add(ChatMessage.text("system", "纠正：" + note.text()));
            }
        }
        messages.add(ChatMessage.text("user", run.input() == null ? "" : run.input()));
        return messages;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
