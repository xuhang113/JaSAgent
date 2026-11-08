package dev.jasagent.api.workflow;

import java.util.List;

public record WorkflowResult(String status, String output, String errorCode, List<CorrectionNote> notes) {
    public WorkflowResult {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
