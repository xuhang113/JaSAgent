package dev.jasagent.api.dsl;

import java.util.List;

public record WorkflowDefinition(String id, List<String> rules, List<String> skills, int budgetTokens, List<WorkflowNode> nodes) {
}
