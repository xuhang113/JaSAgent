package dev.jasagent.core;

import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.workflow.CorrectionNote;

import java.util.List;

public record ContextInput(
        String executionId,
        String rules,
        List<SkillText> skills,
        String taskBoard,
        List<CorrectionNote> notes,
        List<ChatMessage> turns,
        String currentUser,
        int budgetTokens) {
}
