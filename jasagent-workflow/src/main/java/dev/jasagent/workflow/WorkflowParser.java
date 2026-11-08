package dev.jasagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.workflow.WorkflowDefinition.NodeSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WorkflowParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TYPES = Set.of(
            "react", "tool", "plugin", "subagents", "evaluate", "interrupt", "condition", "retrieve", "end");
    private static final Set<String> COMPARES = Set.of("boolean", "equals", "gt", "gte", "lt", "lte", "eq");
    private static final Set<String> NUMERIC = Set.of("gt", "gte", "lt", "lte", "eq");

    private WorkflowParser() {
    }

    public static WorkflowDefinition parse(String json) {
        try {
            return parseTree(MAPPER.readTree(json));
        } catch (AgentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AgentException(ErrorCode.WORKFLOW, "工作流文档无法解析", null, ex);
        }
    }

    public static String exampleAnswerWithTool() {
        try (InputStream input = WorkflowParser.class.getResourceAsStream("/workflows/answer-with-tool.json")) {
            if (input == null) {
                throw new AgentException(ErrorCode.WORKFLOW, "缺少示例工作流");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (AgentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AgentException(ErrorCode.WORKFLOW, "示例工作流无法读取", null, ex);
        }
    }

    public static String defaultReactDocument() {
        return """
                {"id":"default-react","budgetTokens":8000,"nodes":[{"id":"act","type":"react","maxSteps":6,"tools":[],"grounding":false}]}
                """;
    }

    private static WorkflowDefinition parseTree(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new AgentException(ErrorCode.WORKFLOW, "工作流文档必须是对象");
        }
        String id = requiredText(root, "id");
        int budget = root.hasNonNull("budgetTokens") ? root.get("budgetTokens").asInt() : 8000;
        JsonNode rawNodes = root.get("nodes");
        if (rawNodes == null || !rawNodes.isArray() || rawNodes.isEmpty()) {
            throw new AgentException(ErrorCode.WORKFLOW, "工作流至少要有一个节点");
        }
        List<NodeSpec> nodes = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode raw : rawNodes) {
            NodeSpec node = node(raw);
            if (!ids.add(node.id())) {
                throw new AgentException(ErrorCode.WORKFLOW, "节点标识重复");
            }
            nodes.add(node);
        }
        for (NodeSpec node : nodes) {
            if (!"condition".equals(node.type())) {
                continue;
            }
            if (!ids.contains(node.whenTrue()) || !ids.contains(node.whenFalse())) {
                throw new AgentException(ErrorCode.WORKFLOW, "条件分支指向了不存在的节点");
            }
        }
        return new WorkflowDefinition(id, texts(root, "rules"), texts(root, "skills"), budget, List.copyOf(nodes));
    }

    private static NodeSpec node(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new AgentException(ErrorCode.WORKFLOW, "节点必须是对象");
        }
        String id = requiredText(raw, "id");
        if (id.startsWith("__")) {
            throw new AgentException(ErrorCode.WORKFLOW, "节点标识不能以 __ 开头");
        }
        String type = requiredText(raw, "type");
        if (!TYPES.contains(type)) {
            throw new AgentException(ErrorCode.WORKFLOW, "不支持的节点类型");
        }
        Integer maxSteps = integer(raw, "maxSteps");
        String compare = text(raw, "compare");
        if ("react".equals(type) && (maxSteps == null || maxSteps < 1)) {
            throw new AgentException(ErrorCode.WORKFLOW, "react 节点必须写明 maxSteps");
        }
        if ("retrieve".equals(type) && !"input".equals(text(raw, "queryFrom"))) {
            throw new AgentException(ErrorCode.WORKFLOW, "retrieve 的 queryFrom 只能是 input");
        }
        if ("tool".equals(type) && text(raw, "tool") == null) {
            throw new AgentException(ErrorCode.WORKFLOW, "tool 节点必须写明工具名");
        }
        if ("plugin".equals(type) && text(raw, "plugin") == null) {
            throw new AgentException(ErrorCode.WORKFLOW, "plugin 节点必须写明插件标识");
        }
        if ("evaluate".equals(type) && text(raw, "evaluator") == null) {
            throw new AgentException(ErrorCode.WORKFLOW, "evaluate 节点必须写明评估器");
        }
        if ("condition".equals(type)) {
            if (compare == null || !COMPARES.contains(compare)) {
                throw new AgentException(ErrorCode.WORKFLOW, "条件比较不受支持");
            }
            if (text(raw, "whenTrue") == null || text(raw, "whenFalse") == null) {
                throw new AgentException(ErrorCode.WORKFLOW, "条件节点必须写明两个分支");
            }
            if (("equals".equals(compare) || NUMERIC.contains(compare)) && text(raw, "value") == null) {
                throw new AgentException(ErrorCode.WORKFLOW, "条件比较缺少比较值");
            }
        }
        return new NodeSpec(
                id,
                type,
                maxSteps,
                texts(raw, "tools"),
                bool(raw, "grounding"),
                texts(raw, "plugins"),
                text(raw, "pluginFailure"),
                text(raw, "tool"),
                text(raw, "plugin"),
                text(raw, "queryFrom"),
                integer(raw, "topK"),
                texts(raw, "scopes"),
                bool(raw, "required"),
                text(raw, "evaluator"),
                compare,
                text(raw, "value"),
                text(raw, "whenTrue"),
                text(raw, "whenFalse"),
                text(raw, "childWorkflow"),
                texts(raw, "tasks"));
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new AgentException(ErrorCode.WORKFLOW, "工作流缺少 " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asInt();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asBoolean();
    }

    private static List<String> texts(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        value.forEach(item -> items.add(item.asText()));
        return List.copyOf(items);
    }
}
