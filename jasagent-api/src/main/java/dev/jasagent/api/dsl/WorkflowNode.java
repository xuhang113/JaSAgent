package dev.jasagent.api.dsl;

import java.util.List;

public record WorkflowNode(
        String id,
        NodeType type,
        String next,
        Integer maxSteps,
        List<String> tools,
        Boolean grounding,
        List<String> plugins,
        String pluginFailure,
        String compare,
        String left,
        String right,
        String thenNode,
        String elseNode,
        String queryFrom,
        Integer topK,
        List<String> scopes,
        Boolean required,
        String evaluator,
        String childWorkflow,
        String tool,
        String pluginId) {
}
