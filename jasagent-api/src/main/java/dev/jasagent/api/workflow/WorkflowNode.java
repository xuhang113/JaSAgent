package dev.jasagent.api.workflow;

import java.util.List;

public record WorkflowNode(
        String id,
        String type,
        String next,
        int maxSteps,
        List<String> tools,
        boolean grounding,
        List<String> plugins,
        String compare,
        String left,
        String right,
        String thenNode,
        String elseNode,
        String output,
        String tool,
        String arguments,
        boolean required,
        int topK,
        List<String> scopes,
        String queryFrom,
        String evaluator,
        String childWorkflow) {
    public WorkflowNode {
        tools = tools == null ? List.of() : List.copyOf(tools);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
