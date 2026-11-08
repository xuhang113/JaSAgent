package dev.jasagent.workflow;

import java.util.List;

public record PromptContext(
        String role,
        List<String> rules,
        List<String> skills,
        List<String> unfinishedTasks,
        String summary,
        List<Turn> recentTurns,
        List<CorrectionNote> corrections) {

    public record Turn(String role, String content) {
    }

    public record CorrectionNote(String noteId, String source, String text) {
    }
}
