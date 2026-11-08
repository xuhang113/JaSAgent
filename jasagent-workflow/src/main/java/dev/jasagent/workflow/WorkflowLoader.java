package dev.jasagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.workflow.WorkflowDocument;
import dev.jasagent.api.workflow.WorkflowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class WorkflowLoader {
    public static final Set<String> NODE_TYPES = Set.of(
            "react", "tool", "plugin", "subagents", "evaluate", "interrupt", "condition", "retrieve", "end");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowLoader() {
    }

    public static String exampleAnswerWithTool() {
        return """
                {"id":"answer-with-tool","rules":["base"],"skills":["research"],"budgetTokens":8000,"nodes":[
                  {"id":"find","type":"retrieve","queryFrom":"input","topK":5,"scopes":["user","workspace"],"required":false,"next":"act"},
                  {"id":"act","type":"react","maxSteps":6,"tools":["clock","rag.search"],"grounding":true,"next":"grade"},
                  {"id":"grade","type":"evaluate","evaluator":"tool.success.rate","required":false,"next":"done"},
                  {"id":"done","type":"end"}
                ]}
                """;
    }

    public static WorkflowDocument parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<WorkflowNode> nodes = new ArrayList<>();
            for (JsonNode node : root.path("nodes")) {
                String type = node.path("type").asText("");
                if (!NODE_TYPES.contains(type)) {
                    throw new AgentException(ErrorCode.WORKFLOW, "不允许的节点类型 " + type);
                }
                nodes.add(new WorkflowNode(
                        node.path("id").asText(),
                        type,
                        text(node, "next"),
                        node.path("maxSteps").asInt(6),
                        strings(node.get("tools")),
                        node.path("grounding").asBoolean(false),
                        strings(node.get("plugins")),
                        text(node, "compare"),
                        text(node, "left"),
                        text(node, "right"),
                        text(node, "then"),
                        text(node, "else"),
                        text(node, "output"),
                        text(node, "tool"),
                        text(node, "arguments"),
                        node.path("required").asBoolean(false),
                        node.path("topK").asInt(5),
                        strings(node.get("scopes")),
                        text(node, "queryFrom"),
                        text(node, "evaluator"),
                        text(node, "childWorkflow")));
            }
            return new WorkflowDocument(
                    root.path("id").asText(),
                    strings(root.get("rules")),
                    strings(root.get("skills")),
                    root.path("budgetTokens").asInt(8000),
                    nodes);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.WORKFLOW, "工作流无法装载", null, ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }
}
