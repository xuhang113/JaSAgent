package dev.jasagent.workflow;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.workflow.ChatModel.ChatMessage;
import dev.jasagent.workflow.ChatModel.ModelReply;
import dev.jasagent.workflow.ChatModel.ModelRequest;
import dev.jasagent.workflow.ChatModel.ToolCall;
import dev.jasagent.workflow.Evaluator.EvaluationOutcome;
import dev.jasagent.workflow.Evaluator.EvaluationQuery;
import dev.jasagent.workflow.PluginHost.PluginCall;
import dev.jasagent.workflow.Retriever.RetrievalHit;
import dev.jasagent.workflow.Retriever.RetrievalQuery;
import dev.jasagent.workflow.SubagentRunner.SubagentCall;
import dev.jasagent.workflow.WorkflowDefinition.NodeSpec;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.dsl.JsonDslGenerator;
import org.bsc.langgraph4j.otel.OTELWrapCallTraceHook;
import org.bsc.langgraph4j.otel.OTELWrapCallTraceSetParentHook;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

final class WorkflowGraph {
    private final ChatModel chat;
    private final ToolCaller tools;
    private final PluginHost plugins;
    private final Retriever retriever;
    private final Evaluator evaluator;
    private final SubagentRunner subagents;
    private final ObjectStreamStateSerializer<WorkflowState> serializer;

    WorkflowGraph(
            ChatModel chat,
            ToolCaller tools,
            PluginHost plugins,
            Retriever retriever,
            Evaluator evaluator,
            SubagentRunner subagents,
            ObjectStreamStateSerializer<WorkflowState> serializer) {
        this.chat = chat;
        this.tools = tools;
        this.plugins = plugins;
        this.retriever = retriever;
        this.evaluator = evaluator;
        this.subagents = subagents;
        this.serializer = serializer;
    }

    Compiled compiled(WorkflowDefinition definition, BaseCheckpointSaver saver) throws GraphStateException {
        StateGraph<WorkflowState> graph = new StateGraph<>(serializer);
        OTELWrapCallTraceHook<WorkflowState> trace = new OTELWrapCallTraceHook<>(serializer);
        OTELWrapCallTraceSetParentHook<WorkflowState> parent = OTELWrapCallTraceSetParentHook.<WorkflowState>builder()
                .scope("dev.jasagent.workflow")
                .groupName("jasagent.workflow")
                .build();
        graph.addWrapCallNodeHook(trace);
        graph.addWrapCallEdgeHook(trace);
        graph.addWrapCallNodeHook(parent);
        graph.addWrapCallEdgeHook(parent);
        List<String> interrupts = new ArrayList<>();
        assemble(graph, definition, interrupts);
        CompileConfig.Builder config = CompileConfig.builder()
                .checkpointSaver(saver)
                .recursionLimit(recursionLimit(definition));
        if (!interrupts.isEmpty()) {
            config.interruptsBefore(interrupts);
        }
        CompiledGraph<WorkflowState> compiled = graph.compile(config.build());
        return new Compiled(compiled, compiled.reduce(new JsonDslGenerator<>()));
    }

    private static int recursionLimit(WorkflowDefinition definition) {
        int limit = 32;
        for (NodeSpec node : definition.nodes()) {
            limit += node.maxSteps() == null ? 4 : node.maxSteps() * 4;
        }
        return Math.max(64, limit);
    }

    private void assemble(StateGraph<WorkflowState> graph, WorkflowDefinition definition, List<String> interrupts)
            throws GraphStateException {
        Map<String, NodeSpec> byId = new LinkedHashMap<>();
        for (NodeSpec node : definition.nodes()) {
            byId.put(node.id(), node);
            if ("interrupt".equals(node.type())) {
                interrupts.add(node.id());
            }
        }
        Set<String> targets = new LinkedHashSet<>();
        for (NodeSpec node : definition.nodes()) {
            if ("condition".equals(node.type())) {
                targets.add(node.whenTrue());
                targets.add(node.whenFalse());
            }
        }
        List<NodeSpec> spine = new ArrayList<>();
        for (NodeSpec node : definition.nodes()) {
            addNode(graph, node);
            if (!targets.contains(node.id())) {
                spine.add(node);
            }
        }
        if (spine.isEmpty()) {
            throw new AgentException(ErrorCode.WORKFLOW, "工作流没有入口节点");
        }
        graph.addEdge(START, entry(spine.get(0)));
        for (int index = 0; index < spine.size(); index++) {
            NodeSpec node = spine.get(index);
            String next = index + 1 < spine.size() ? entry(spine.get(index + 1)) : END;
            link(graph, node, next, byId);
        }
        for (NodeSpec node : definition.nodes()) {
            if (!"condition".equals(node.type())) {
                continue;
            }
            String rejoin = rejoin(spine, node);
            link(graph, byId.get(node.whenTrue()), rejoin, byId);
            link(graph, byId.get(node.whenFalse()), rejoin, byId);
        }
    }

    private static String rejoin(List<NodeSpec> spine, NodeSpec condition) {
        for (int index = 0; index < spine.size(); index++) {
            if (spine.get(index).id().equals(condition.id())) {
                return index + 1 < spine.size() ? entry(spine.get(index + 1)) : END;
            }
        }
        return END;
    }

    private void addNode(StateGraph<WorkflowState> graph, NodeSpec node) throws GraphStateException {
        switch (node.type()) {
            case "react" -> {
                graph.addNode(modelId(node.id()), (state, config) -> reactModel(state, node));
                graph.addNode(toolsId(node.id()), (state, config) -> reactTools(state, node));
            }
            case "tool" -> graph.addNode(node.id(), (state, config) -> toolNode(state, node));
            case "plugin" -> graph.addNode(node.id(), (state, config) -> pluginNode(state, node));
            case "retrieve" -> graph.addNode(node.id(), (state, config) -> retrieveNode(state, node));
            case "evaluate" -> graph.addNode(node.id(), (state, config) -> evaluateNode(state, node));
            case "subagents" -> graph.addNode(node.id(), (state, config) -> subagentNode(state, node));
            case "interrupt", "end", "condition" -> graph.addNode(node.id(), (state, config) -> passThrough(state, node));
            default -> throw new AgentException(ErrorCode.WORKFLOW, "不支持的节点类型");
        }
    }

    private void link(
            StateGraph<WorkflowState> graph,
            NodeSpec node,
            String next,
            Map<String, NodeSpec> byId) throws GraphStateException {
        switch (node.type()) {
            case "react" -> {
                graph.addEdge(toolsId(node.id()), modelId(node.id()));
                graph.addConditionalEdges(
                        modelId(node.id()),
                        state -> CompletableFuture.completedFuture(StateValues.text(state, "route")),
                        Map.of("tools", toolsId(node.id()), "retry", modelId(node.id()), "next", next));
            }
            case "condition" -> graph.addConditionalEdges(
                    node.id(),
                    state -> CompletableFuture.completedFuture(conditionRoute(state, node)),
                    Map.of(
                            "true", entry(byId.get(node.whenTrue())),
                            "false", entry(byId.get(node.whenFalse()))));
            case "end" -> graph.addEdge(node.id(), END);
            default -> graph.addEdge(node.id(), next);
        }
    }

    private static String conditionRoute(WorkflowState state, NodeSpec node) {
        Object output = state.data().get("lastOutput");
        boolean matched = switch (node.compare()) {
            case "boolean" -> Boolean.TRUE.equals(output);
            case "equals" -> node.value().equals(output == null ? "" : String.valueOf(output));
            default -> compareNumber(output, node);
        };
        return matched ? "true" : "false";
    }

    private static boolean compareNumber(Object output, NodeSpec node) {
        double actual = output instanceof Number number
                ? number.doubleValue()
                : Double.parseDouble(String.valueOf(output));
        double expected = Double.parseDouble(node.value());
        return switch (node.compare()) {
            case "gt" -> actual > expected;
            case "gte" -> actual >= expected;
            case "lt" -> actual < expected;
            case "lte" -> actual <= expected;
            case "eq" -> Double.compare(actual, expected) == 0;
            default -> throw new AgentException(ErrorCode.WORKFLOW, "条件比较不受支持");
        };
    }

    private CompletableFuture<Map<String, Object>> passThrough(WorkflowState state, NodeSpec node) {
        List<String> errors = newErrorList();
        return phases(state, node, "onNodeStart", errors)
                .thenCompose(ignored -> phases(state, node, "onNodeEnd", errors))
                .thenApply(ignored -> {
                    Map<String, Object> update = updates(state, node, errors);
                    if ("end".equals(node.type()) && StateValues.text(state, "output").isBlank()) {
                        update.put("output", StateValues.text(state, "lastOutput"));
                    }
                    return update;
                });
    }

    private CompletableFuture<Map<String, Object>> reactModel(WorkflowState state, NodeSpec node) {
        int step = StateValues.number(state, stepKey(node.id()));
        if (step >= node.maxSteps()) {
            return CompletableFuture.failedFuture(new AgentException(
                    ErrorCode.WORKFLOW_MAX_STEPS,
                    "已达到 maxSteps",
                    StateValues.text(state, "executionId"),
                    null));
        }
        List<String> errors = newErrorList();
        boolean started = Boolean.TRUE.equals(state.data().get(startedKey(node.id())));
        CompletableFuture<Void> start = started
                ? CompletableFuture.completedFuture(null)
                : phases(state, node, "onNodeStart", errors);
        return start.thenCompose(ignored -> phases(state, node, "beforeModel", errors))
                .thenCompose(ignored -> complete(state, node))
                .thenCompose(reply -> phases(state, node, "afterModel", errors).thenApply(ignored -> reply))
                .thenCompose(reply -> afterModel(state, node, reply, errors, step));
    }

    private CompletableFuture<ModelReply> complete(WorkflowState state, NodeSpec node) {
        List<ChatMessage> messages = PromptMessages.assemble(state);
        ModelRequest request = new ModelRequest(
                StateValues.text(state, "executionId"),
                "react",
                messages,
                node.tools());
        try {
            return chat.complete(request).toCompletableFuture().handle((reply, error) -> {
                if (error != null) {
                    throw failure(error, ErrorCode.LLM, StateValues.text(state, "executionId"));
                }
                return reply;
            });
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(failure(ex, ErrorCode.LLM, StateValues.text(state, "executionId")));
        }
    }

    private CompletableFuture<Map<String, Object>> afterModel(
            WorkflowState state,
            NodeSpec node,
            ModelReply reply,
            List<String> errors,
            int step) {
        List<ToolCall> calls = reply.toolCalls() == null ? List.of() : reply.toolCalls();
        if (!calls.isEmpty()) {
            return finish(state, node, errors, step, "tools", reply, null, 0);
        }
        if (Boolean.TRUE.equals(node.grounding())) {
            String corpus = StateValues.text(state, "input")
                    + "\n" + String.join("\n", StateValues.strings(state, "toolTexts"))
                    + "\n" + String.join("\n", StateValues.strings(state, "hits"));
            List<String> missing = Grounding.unmatched(reply.content(), corpus);
            int retries = StateValues.number(state, groundingKey(node.id()));
            if (!missing.isEmpty()) {
                List<Map<String, String>> notes = new ArrayList<>(StateValues.maps(state, "corrections"));
                notes.add(groundingNote(state));
                String route = retries < 1 ? "retry" : "next";
                return finish(state, node, errors, step, route, reply, notes, retries + 1);
            }
        }
        return finish(state, node, errors, step, "next", reply, null, 0);
    }

    private CompletableFuture<Map<String, Object>> finish(
            WorkflowState state,
            NodeSpec node,
            List<String> errors,
            int step,
            String route,
            ModelReply reply,
            List<Map<String, String>> notes,
            int groundingRetries) {
        CompletableFuture<Void> end = "next".equals(route)
                ? phases(state, node, "onNodeEnd", errors)
                : CompletableFuture.completedFuture(null);
        return end.thenApply(ignored -> {
            Map<String, Object> update = updates(state, node, errors);
            update.put(startedKey(node.id()), true);
            update.put(stepKey(node.id()), step + 1);
            update.put("route", route);
            if ("tools".equals(route)) {
                update.put("pendingTools", pending(reply));
            }
            if (notes != null) {
                update.put("corrections", notes);
                update.put(groundingKey(node.id()), groundingRetries);
            }
            if ("next".equals(route)) {
                String content = reply.content() == null ? "" : reply.content();
                update.put("lastOutput", content);
                update.put("output", content);
            }
            String thinking = reply.thinking() == null ? "" : reply.thinking().trim();
            if (!thinking.isEmpty()) {
                update.put("thinking", thinking);
            }
            return update;
        });
    }

    private CompletableFuture<Map<String, Object>> reactTools(WorkflowState state, NodeSpec node) {
        List<String> errors = newErrorList();
        List<Map<String, String>> pending = StateValues.maps(state, "pendingTools");
        List<CompletableFuture<String>> calls = new ArrayList<>();
        for (Map<String, String> call : pending) {
            calls.add(phases(state, node, "beforeTool", errors)
                    .thenCompose(ignored -> callTool(state, call.get("name"), call.getOrDefault("arguments", "")))
                    .thenCompose(result -> phases(state, node, "afterTool", errors).thenApply(ignored -> result)));
        }
        return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<String> texts = new ArrayList<>(StateValues.strings(state, "toolTexts"));
            for (CompletableFuture<String> call : calls) {
                texts.add(call.join());
            }
            Map<String, Object> update = updates(state, node, errors);
            update.put("toolTexts", texts);
            update.put("pendingTools", new ArrayList<>());
            update.put("lastOutput", String.join("\n", texts));
            return update;
        });
    }

    private CompletableFuture<Map<String, Object>> toolNode(WorkflowState state, NodeSpec node) {
        List<String> errors = newErrorList();
        return phases(state, node, "onNodeStart", errors)
                .thenCompose(ignored -> phases(state, node, "beforeTool", errors))
                .thenCompose(ignored -> callTool(state, node.tool(), StateValues.text(state, "input")))
                .thenCompose(result -> phases(state, node, "afterTool", errors).thenApply(ignored -> result))
                .thenCompose(result -> phases(state, node, "onNodeEnd", errors).thenApply(ignored -> result))
                .thenApply(result -> {
                    Map<String, Object> update = updates(state, node, errors);
                    update.put("lastOutput", result);
                    return update;
                });
    }

    private CompletableFuture<Map<String, Object>> pluginNode(WorkflowState state, NodeSpec node) {
        return passThrough(state, node);
    }

    private CompletableFuture<Map<String, Object>> retrieveNode(WorkflowState state, NodeSpec node) {
        List<String> errors = newErrorList();
        RetrievalQuery query = new RetrievalQuery(
                StateValues.text(state, "executionId"),
                StateValues.text(state, "input"),
                node.topK() == null ? 5 : node.topK(),
                node.scopes());
        return phases(state, node, "onNodeStart", errors)
                .thenCompose(ignored -> {
                    try {
                        return retriever.retrieve(query).toCompletableFuture();
                    } catch (RuntimeException ex) {
                        return CompletableFuture.failedFuture(failure(ex, ErrorCode.WORKFLOW, query.executionId()));
                    }
                })
                .thenCompose(hits -> phases(state, node, "onNodeEnd", errors).thenApply(ignored -> hits))
                .thenApply(hits -> {
                    List<RetrievalHit> found = hits == null ? List.of() : hits;
                    if (found.isEmpty() && Boolean.TRUE.equals(node.required())) {
                        throw new AgentException(ErrorCode.WORKFLOW, "检索结果为空", query.executionId(), null);
                    }
                    List<String> contents = new ArrayList<>();
                    for (RetrievalHit hit : found) {
                        contents.add(hit.content() == null ? "" : hit.content());
                    }
                    Map<String, Object> update = updates(state, node, errors);
                    update.put("hits", contents);
                    update.put("lastOutput", String.join("\n", contents));
                    return update;
                });
    }

    private CompletableFuture<Map<String, Object>> evaluateNode(WorkflowState state, NodeSpec node) {
        List<String> errors = newErrorList();
        String executionId = StateValues.text(state, "executionId");
        EvaluationQuery query = new EvaluationQuery(
                executionId,
                node.evaluator(),
                StateValues.text(state, "output"),
                StateValues.strings(state, "toolTexts"));
        return phases(state, node, "onNodeStart", errors)
                .thenCompose(ignored -> {
                    try {
                        return evaluator.evaluate(query).toCompletableFuture();
                    } catch (RuntimeException ex) {
                        return CompletableFuture.failedFuture(failure(ex, ErrorCode.WORKFLOW, executionId));
                    }
                })
                .thenCompose(outcome -> phases(state, node, "onNodeEnd", errors).thenApply(ignored -> outcome))
                .thenApply(outcome -> {
                    if (!outcome.passed() && Boolean.TRUE.equals(node.required())) {
                        throw new AgentException(ErrorCode.WORKFLOW, "评估未通过", executionId, null);
                    }
                    Map<String, Object> update = updates(state, node, errors);
                    update.put("evaluationPassed", outcome.passed());
                    update.put("lastOutput", outcome.passed());
                    return update;
                });
    }

    private CompletableFuture<Map<String, Object>> subagentNode(WorkflowState state, NodeSpec node) {
        List<String> errors = newErrorList();
        String executionId = StateValues.text(state, "executionId");
        String childId = node.childWorkflow() == null ? "" : node.childWorkflow();
        String workflowJson = childId.isBlank() ? WorkflowParser.defaultReactDocument() : "";
        List<String> tasks = node.tasks().isEmpty()
                ? List.of(StateValues.text(state, "input"))
                : node.tasks();
        SubagentCall call = new SubagentCall(executionId, childId, workflowJson, tasks);
        return phases(state, node, "onNodeStart", errors)
                .thenCompose(ignored -> {
                    try {
                        return subagents.run(call).toCompletableFuture();
                    } catch (RuntimeException ex) {
                        return CompletableFuture.failedFuture(failure(ex, ErrorCode.WORKFLOW, executionId));
                    }
                })
                .thenCompose(result -> phases(state, node, "onNodeEnd", errors).thenApply(ignored -> result))
                .thenApply(result -> {
                    String text = result == null ? "" : result;
                    Map<String, Object> update = updates(state, node, errors);
                    update.put("lastOutput", text);
                    update.put("output", text);
                    return update;
                });
    }

    private CompletableFuture<String> callTool(WorkflowState state, String name, String arguments) {
        String executionId = StateValues.text(state, "executionId");
        try {
            return tools.call(executionId, name, arguments == null ? "" : arguments)
                    .toCompletableFuture()
                    .handle((result, error) -> {
                        if (error != null) {
                            throw failure(error, ErrorCode.TOOL, executionId);
                        }
                        return result == null ? "" : result;
                    });
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(failure(ex, ErrorCode.TOOL, executionId));
        }
    }

    private CompletableFuture<Void> phases(WorkflowState state, NodeSpec node, String phase, List<String> errors) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String pluginId : pluginsOf(node)) {
            chain = chain.thenCompose(ignored -> invokePlugin(state, node, phase, pluginId, errors));
        }
        return chain;
    }

    private CompletableFuture<Void> invokePlugin(
            WorkflowState state,
            NodeSpec node,
            String phase,
            String pluginId,
            List<String> errors) {
        String executionId = StateValues.text(state, "executionId");
        PluginCall call = new PluginCall(executionId, node.id(), phase, pluginId);
        try {
            return plugins.invoke(call).toCompletableFuture().handle((ignored, error) -> {
                if (error == null) {
                    return null;
                }
                Throwable cause = root(error);
                if ("fail".equals(node.pluginFailure())) {
                    if (cause instanceof AgentException agent) {
                        throw agent;
                    }
                    throw new AgentException(
                            ErrorCode.PLUGIN,
                            cause.getMessage() == null ? "插件失败" : cause.getMessage(),
                            executionId,
                            cause);
                }
                synchronized (errors) {
                    errors.add(phase + ":" + pluginId + ":" + (cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage()));
                }
                return null;
            });
        } catch (RuntimeException ex) {
            if ("fail".equals(node.pluginFailure())) {
                return CompletableFuture.failedFuture(failure(ex, ErrorCode.PLUGIN, executionId));
            }
            synchronized (errors) {
                errors.add(phase + ":" + pluginId + ":" + ex.getMessage());
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static List<String> pluginsOf(NodeSpec node) {
        if (node.plugins() != null && !node.plugins().isEmpty()) {
            return node.plugins();
        }
        if ("plugin".equals(node.type()) && node.plugin() != null) {
            return List.of(node.plugin());
        }
        return List.of();
    }

    private static List<Map<String, String>> pending(ModelReply reply) {
        List<Map<String, String>> pending = new ArrayList<>();
        for (ToolCall call : reply.toolCalls()) {
            Map<String, String> item = new HashMap<>();
            item.put("id", call.id() == null || call.id().isBlank() ? UUID.randomUUID().toString() : call.id());
            item.put("name", call.name());
            item.put("arguments", call.arguments() == null ? "" : call.arguments());
            pending.add(item);
        }
        return pending;
    }

    private static Map<String, String> groundingNote(WorkflowState state) {
        Map<String, String> note = new HashMap<>();
        note.put("noteId", UUID.randomUUID().toString());
        note.put("sessionId", StateValues.text(state, "sessionId"));
        note.put("executionId", StateValues.text(state, "executionId"));
        note.put("createdAt", Instant.now().toString());
        note.put("source", "GROUNDING");
        note.put("text", "回答中的数字或引号片段没有出现在工具结果、检索命中或用户消息里");
        return note;
    }

    private static Map<String, Object> updates(WorkflowState state, NodeSpec node, List<String> errors) {
        Map<String, Object> update = new HashMap<>();
        update.put(startedKey(node.id()), true);
        List<String> all = new ArrayList<>(StateValues.strings(state, "pluginErrors"));
        synchronized (errors) {
            all.addAll(errors);
        }
        update.put("pluginErrors", all);
        return update;
    }

    private static List<String> newErrorList() {
        return new ArrayList<>();
    }

    private static AgentException failure(Throwable error, ErrorCode fallback, String executionId) {
        Throwable cause = root(error);
        if (cause instanceof AgentException agent) {
            return agent;
        }
        return new AgentException(
                fallback,
                cause.getMessage() == null ? "调用失败" : cause.getMessage(),
                executionId,
                cause);
    }

    private static Throwable root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String entry(NodeSpec node) {
        return "react".equals(node.type()) ? modelId(node.id()) : node.id();
    }

    private static String modelId(String id) {
        return id + "__model";
    }

    private static String toolsId(String id) {
        return id + "__tools";
    }

    private static String stepKey(String id) {
        return "steps." + id;
    }

    private static String startedKey(String id) {
        return "started." + id;
    }

    private static String groundingKey(String id) {
        return "groundingRetries." + id;
    }

    record Compiled(CompiledGraph<WorkflowState> graph, String dsl) {
    }
}
