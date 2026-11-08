package dev.jasagent.api.workflow;

import java.util.List;

public record WorkflowDocument(
        String id,
        List<String> rules,
        List<String> skills,
        int budgetTokens,
        List<WorkflowNode> nodes) {
    public WorkflowDocument {
        rules = rules == null ? List.of() : List.copyOf(rules);
        skills = skills == null ? List.of() : List.copyOf(skills);
        nodes = List.copyOf(nodes);
    }

    public List<String> nodeIds() {
        return nodes.stream().map(WorkflowNode::id).toList();
    }
}
