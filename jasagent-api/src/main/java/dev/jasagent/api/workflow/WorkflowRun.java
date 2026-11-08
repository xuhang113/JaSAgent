package dev.jasagent.api.workflow;

import dev.jasagent.api.llm.ChatMessage;

import java.util.List;

public record WorkflowRun(
        String executionId,
        String sessionId,
        String userId,
        String input,
        String rulesText,
        String skillsText,
        String taskBoard,
        String summary,
        List<ChatMessage> recentTurns,
        List<CorrectionNote> notes,
        int budgetTokens) {
    public WorkflowRun {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
