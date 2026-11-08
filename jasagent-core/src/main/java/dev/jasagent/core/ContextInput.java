package dev.jasagent.core;

import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.llm.ChatMessage;

import java.util.List;

public record ContextInput(
        String executionId,
        String role,
        String rules,
        List<SkillText> skills,
        String taskBoard,
        List<CorrectionNote> notes,
        List<ChatMessage> turns,
        String currentUser,
        int budgetTokens) {
    public ContextInput {
        skills = skills == null ? List.of() : List.copyOf(skills);
        notes = notes == null ? List.of() : List.copyOf(notes);
        turns = turns == null ? List.of() : List.copyOf(turns);
        role = role == null ? "" : role;
        rules = rules == null ? "" : rules;
        taskBoard = taskBoard == null ? "" : taskBoard;
        currentUser = currentUser == null ? "" : currentUser;
    }
}
