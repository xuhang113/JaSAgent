package dev.jasagent.workflow;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.ToolCall;
import dev.jasagent.api.llm.ToolDefinition;
import dev.jasagent.api.workflow.CheckpointStore;
import dev.jasagent.api.workflow.CorrectionNote;
import dev.jasagent.api.workflow.EvalResult;
import dev.jasagent.api.workflow.Evaluator;
import dev.jasagent.api.workflow.PluginHost;
import dev.jasagent.api.workflow.RetrievalResult;
import dev.jasagent.api.workflow.Retriever;
import dev.jasagent.api.workflow.SubagentRunner;
import dev.jasagent.api.workflow.ToolInvoker;
import dev.jasagent.api.workflow.ToolResult;
import dev.jasagent.api.workflow.WorkflowDocument;
import dev.jasagent.api.workflow.WorkflowNode;
import dev.jasagent.api.workflow.WorkflowResult;
import dev.jasagent.api.workflow.WorkflowRun;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class JsonWorkflowEngine {
    private final ChatModel model;
    private final ToolInvoker tools;
    private final CheckpointStore checkpoints;
    private final Retriever retriever;
    private final Evaluator evaluator;
    private final SubagentRunner subagents;
    private final PluginHost plugins;

    public JsonWorkflowEngine(
            ChatModel model,
            ToolInvoker tools,
            CheckpointStore checkpoints,
            Retriever retriever,
            Evaluator evaluator,
            SubagentRunner subagents,
            PluginHost plugins) {
        this.model = model;
        this.tools = tools;
        this.checkpoints = checkpoints;
        this.retriever = retriever;
        this.evaluator = evaluator;
        this.subagents = subagents;
        this.plugins = plugins;
    }

    public CompletionStage<WorkflowResult> execute(WorkflowDocument document, WorkflowRun run) {
        return VirtualIo.supply(() -> executeSync(document, run));
    }

    private WorkflowResult executeSync(WorkflowDocument document, WorkflowRun run) {
        Map<String, WorkflowNode> byId = new LinkedHashMap<>();
        for (WorkflowNode node : document.nodes()) {
            byId.put(node.id(), node);
        }
        WorkflowNode current = document.nodes().isEmpty() ? null : document.nodes().get(0);
        String previous = run.input() == null ? "" : run.input();
        List<CorrectionNote> notes = new ArrayList<>(run.notes());
        String retrievalEvidence = "";
        while (current != null) {
            try {
                commit(run.executionId(), current.id(), previous);
            } catch (AgentException ex) {
                return new WorkflowResult("FAILED", previous, ex.code().name(), notes);
            }
            switch (current.type()) {
                case "end" -> {
                    String output = current.output() == null ? previous : current.output();
                    return new WorkflowResult("SUCCEEDED", output, null, notes);
                }
                case "interrupt" -> {
                    return new WorkflowResult("SUSPENDED", previous, null, notes);
                }
                case "tool" -> previous = callTool(current.tool(), current.arguments() == null ? "{}" : current.arguments(), run.executionId());
                case "retrieve" -> {
                    RetrievalResult retrieved = join(retriever.retrieve(
                            "input".equals(current.queryFrom()) || current.queryFrom() == null ? run.input() : previous,
                            current.topK(),
                            current.scopes(),
                            run.executionId()));
                    if (retrieved.hits().isEmpty() && current.required()) {
                        return new WorkflowResult("FAILED", "", "RAG", notes);
                    }
                    previous = String.join("\n", retrieved.hits());
                    retrievalEvidence = previous;
                }
                case "condition" -> {
                    current = byId.get(conditionMatches(current, previous) ? current.thenNode() : current.elseNode());
                    continue;
                }
                case "evaluate" -> {
                    EvalResult evaluation = join(evaluator.evaluate(current.evaluator(), run.executionId(), previous));
                    if (!evaluation.passed() && current.required()) {
                        return new WorkflowResult("FAILED", previous, "WORKFLOW", notes);
                    }
                }
                case "plugin" -> phase(current.plugins().isEmpty() ? List.of(current.tool()) : current.plugins(), "onNodeStart", run.executionId(), current.id());
                case "subagents" -> {
                    String child = current.childWorkflow() == null ? defaultReactWorkflow() : current.childWorkflow();
                    List<String> outputs = join(subagents.run(run.executionId(), child, List.of(previous)));
                    previous = String.join("\n", outputs);
                }
                case "react" -> {
                    ReactStop stop = react(current, run, notes, previous, retrievalEvidence);
                    if (stop.failed()) {
                        return new WorkflowResult("FAILED", stop.output(), stop.errorCode(), notes);
                    }
                    previous = stop.output();
                }
                default -> {
                    return new WorkflowResult("FAILED", previous, "WORKFLOW", notes);
                }
            }
            current = current.next() == null ? null : byId.get(current.next());
        }
        return new WorkflowResult("SUCCEEDED", previous, null, notes);
    }

    private ReactStop react(WorkflowNode node, WorkflowRun run, List<CorrectionNote> notes, String previous, String retrievalEvidence) {
        phase(node.plugins(), "onNodeStart", run.executionId(), node.id());
        List<ChatMessage> recent = new ArrayList<>(run.recentTurns());
        StringBuilder toolEvidence = new StringBuilder();
        String answer = null;
        int groundingRetries = 0;
        int steps = 0;
        while (steps < node.maxSteps()) {
            phase(node.plugins(), "beforeModel", run.executionId(), node.id());
            ChatResponse response = join(model.complete(new ChatRequest(
                    LlmTask.REACT,
                    PromptMessages.assemble(runWith(run, recent, notes), recent, notes),
                    definitions(node.tools()),
                    null,
                    run.executionId())));
            phase(node.plugins(), "afterModel", run.executionId(), node.id());
            steps++;
            if (!response.message().toolCalls().isEmpty()) {
                phase(node.plugins(), "beforeTool", run.executionId(), node.id());
                List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
                for (ToolCall call : response.message().toolCalls()) {
                    futures.add(tools.call(call.name(), call.argumentsJson() == null ? "{}" : call.argumentsJson(), run.executionId()).toCompletableFuture());
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                phase(node.plugins(), "afterTool", run.executionId(), node.id());
                recent.add(response.message());
                for (CompletableFuture<ToolResult> future : futures) {
                    ToolResult result = future.join();
                    toolEvidence.append(result.text() == null ? "" : result.text());
                    recent.add(ChatMessage.text("tool", result.text()));
                }
                continue;
            }
            answer = response.message().content() == null ? "" : response.message().content();
            if (node.grounding()) {
                String evidence = (run.input() == null ? "" : run.input()) + toolEvidence + retrievalEvidence;
                if (!GroundingCheck.grounded(answer, evidence) && groundingRetries < 1) {
                    notes.add(new CorrectionNote(
                            UUID.randomUUID().toString(),
                            run.sessionId(),
                            run.executionId(),
                            Instant.now(),
                            "GROUNDING",
                            "回答里的数字或引号片段缺少依据"));
                    groundingRetries++;
                    continue;
                }
            }
            break;
        }
        phase(node.plugins(), "onNodeEnd", run.executionId(), node.id());
        if (answer == null) {
            return new ReactStop(true, "", "WORKFLOW_MAX_STEPS");
        }
        return new ReactStop(false, answer, null);
    }

    private static WorkflowRun runWith(WorkflowRun run, List<ChatMessage> recent, List<CorrectionNote> notes) {
        return new WorkflowRun(
                run.executionId(),
                run.sessionId(),
                run.userId(),
                run.input(),
                run.rulesText(),
                run.skillsText(),
                run.taskBoard(),
                run.summary(),
                recent,
                notes,
                run.budgetTokens());
    }

    private void phase(List<String> pluginIds, String phase, String executionId, String nodeId) {
        if (plugins == null || pluginIds == null) {
            return;
        }
        for (String pluginId : pluginIds) {
            if (pluginId == null || pluginId.isBlank()) {
                continue;
            }
            try {
                join(plugins.invoke(pluginId, phase, executionId, nodeId));
            } catch (RuntimeException ex) {
                throw ex;
            }
        }
    }

    private String callTool(String name, String arguments, String executionId) {
        ToolResult result = join(tools.call(name, arguments, executionId));
        return result.text() == null ? "" : result.text();
    }

    private void commit(String executionId, String nodeId, String state) {
        try {
            checkpoints.commit(executionId, nodeId, state == null ? "" : state).toCompletableFuture().join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof AgentException agent) {
                throw agent;
            }
            throw new AgentException(ErrorCode.PERSIST, cause.getMessage(), executionId, cause);
        }
    }

    private static boolean conditionMatches(WorkflowNode node, String previous) {
        String compare = node.compare() == null ? "eq" : node.compare();
        String value = previous == null ? "" : previous;
        return switch (compare) {
            case "bool" -> Boolean.parseBoolean(value);
            case "gt" -> number(value) > number(node.right());
            case "gte" -> number(value) >= number(node.right());
            case "lt" -> number(value) < number(node.right());
            case "lte" -> number(value) <= number(node.right());
            default -> value.equals(node.right() == null ? "" : node.right());
        };
    }

    private static double number(String value) {
        return Double.parseDouble(value);
    }

    private static List<ToolDefinition> definitions(List<String> names) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (String name : names) {
            definitions.add(new ToolDefinition(name, name, "{\"type\":\"object\"}"));
        }
        return definitions;
    }

    private static String defaultReactWorkflow() {
        return "{\"id\":\"default-react\",\"nodes\":[{\"id\":\"act\",\"type\":\"react\",\"maxSteps\":6}]}";
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    private record ReactStop(boolean failed, String output, String errorCode) {
    }
}
