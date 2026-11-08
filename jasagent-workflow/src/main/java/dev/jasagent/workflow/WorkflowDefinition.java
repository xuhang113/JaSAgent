package dev.jasagent.workflow;

import java.util.List;

public record WorkflowDefinition(
        String id,
        List<String> rules,
        List<String> skills,
        int budgetTokens,
        List<NodeSpec> nodes) {

    public record NodeSpec(
            String id,
            String type,
            Integer maxSteps,
            List<String> tools,
            Boolean grounding,
            List<String> plugins,
            String pluginFailure,
            String tool,
            String plugin,
            String queryFrom,
            Integer topK,
            List<String> scopes,
            Boolean required,
            String evaluator,
            String compare,
            String value,
            String whenTrue,
            String whenFalse,
            String childWorkflow,
            List<String> tasks) {
    }
}
