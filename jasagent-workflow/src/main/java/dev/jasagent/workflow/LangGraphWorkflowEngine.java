package dev.jasagent.workflow;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.workflow.PromptContext.CorrectionNote;
import dev.jasagent.workflow.PromptContext.Turn;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class LangGraphWorkflowEngine {
    private final WorkflowGraph graph;
    private final ObjectStreamStateSerializer<WorkflowState> serializer;
    private final Map<String, BaseCheckpointSaver> savers = new HashMap<>();

    public LangGraphWorkflowEngine(
            ChatModel chat,
            ToolCaller tools,
            PluginHost plugins,
            Retriever retriever,
            Evaluator evaluator,
            SubagentRunner subagents) {
        this.serializer = new ObjectStreamStateSerializer<>(WorkflowState::new);
        this.graph = new WorkflowGraph(
                Objects.requireNonNull(chat),
                Objects.requireNonNull(tools),
                Objects.requireNonNull(plugins),
                Objects.requireNonNull(retriever),
                Objects.requireNonNull(evaluator),
                Objects.requireNonNull(subagents),
                serializer);
    }

    public CompletionStage<WorkflowRun> execute(WorkflowRequest request) {
        return VirtualIo.supply(() -> run(request, false));
    }

    public CompletionStage<WorkflowRun> resume(WorkflowRequest request) {
        return VirtualIo.supply(() -> run(request, true));
    }

    private WorkflowRun run(WorkflowRequest request, boolean resume) {
        BaseCheckpointSaver saver = null;
        String dsl = "";
        try {
            saver = saver(request);
            WorkflowGraph.Compiled compiled = graph.compiled(request.workflow(), saver);
            dsl = compiled.dsl();
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(request.executionId())
                    .recursionLimit(Math.max(64, request.workflow().nodes().size() * 8 + maxSteps(request) * 4))
                    .build();
            GraphInput input = resume ? GraphInput.resume() : GraphInput.args(initial(request));
            GraphResult result = compiled.graph().invokeSync(input, config);
            if (result.isInterruptionMetadata()) {
                InterruptionMetadata<WorkflowState> interruption = result.asInterruptionMetadata();
                return snapshot(request, interruption.state(), "SUSPENDED", null, dsl, saver, "");
            }
            if (result.isStateData()) {
                return snapshot(request, result.asState(), "SUCCEEDED", null, dsl, saver, "");
            }
            if (result.isNodeOutput()) {
                return snapshot(request, (WorkflowState) result.asNodeOutput().state(), "SUCCEEDED", null, dsl, saver, "");
            }
            return failed(request, new AgentException(ErrorCode.WORKFLOW, "图没有返回状态"), dsl, saver);
        } catch (Throwable error) {
            return failed(request, error, dsl, saver);
        }
    }

    private WorkflowRun failed(WorkflowRequest request, Throwable error, String dsl, BaseCheckpointSaver saver) {
        AgentException agent = agent(error, request.executionId());
        return new WorkflowRun(
                request.executionId(),
                "FAILED",
                "",
                agent.code().name(),
                dsl,
                List.of(),
                null,
                List.of(),
                saver == null ? "" : saver.getClass().getName(),
                chain(error));
    }

    private WorkflowRun snapshot(
            WorkflowRequest request,
            WorkflowState state,
            String status,
            String errorCode,
            String dsl,
            BaseCheckpointSaver saver,
            String detail) {
        Boolean evaluation = null;
        Object passed = state.data().get("evaluationPassed");
        if (passed instanceof Boolean value) {
            evaluation = value;
        }
        List<String> sources = new ArrayList<>();
        for (Map<String, String> note : StateValues.maps(state, "corrections")) {
            sources.add(note.getOrDefault("source", ""));
        }
        return new WorkflowRun(
                request.executionId(),
                status,
                StateValues.text(state, "output"),
                errorCode,
                dsl,
                StateValues.strings(state, "pluginErrors"),
                evaluation,
                List.copyOf(sources),
                saver.getClass().getName(),
                detail);
    }

    private BaseCheckpointSaver saver(WorkflowRequest request) throws Exception {
        String key = CheckpointSavers.postgres(request.jdbcUrl())
                ? request.jdbcUrl()
                : request.checkpointDatabase().toString();
        BaseCheckpointSaver existing = savers.get(key);
        if (existing != null) {
            return existing;
        }
        BaseCheckpointSaver created = CheckpointSavers.open(request.checkpointDatabase(), request.jdbcUrl(), serializer);
        savers.put(key, created);
        return created;
    }

    private static int maxSteps(WorkflowRequest request) {
        int steps = 1;
        for (WorkflowDefinition.NodeSpec node : request.workflow().nodes()) {
            if (node.maxSteps() != null) {
                steps = Math.max(steps, node.maxSteps());
            }
        }
        return steps;
    }

    private static Map<String, Object> initial(WorkflowRequest request) {
        PromptContext context = request.context();
        Map<String, Object> state = new HashMap<>();
        state.put("executionId", request.executionId());
        state.put("sessionId", request.sessionId() == null ? "" : request.sessionId());
        state.put("userId", request.userId() == null ? "" : request.userId());
        state.put("input", request.input() == null ? "" : request.input());
        state.put("role", context.role() == null ? "" : context.role());
        state.put("rules", new ArrayList<>(context.rules() == null ? List.of() : context.rules()));
        state.put("skills", new ArrayList<>(context.skills() == null ? List.of() : context.skills()));
        state.put("tasks", new ArrayList<>(context.unfinishedTasks() == null ? List.of() : context.unfinishedTasks()));
        state.put("summary", context.summary() == null ? "" : context.summary());
        List<Map<String, String>> turns = new ArrayList<>();
        if (context.recentTurns() != null) {
            for (Turn turn : context.recentTurns()) {
                Map<String, String> item = new HashMap<>();
                item.put("role", turn.role() == null ? "" : turn.role());
                item.put("content", turn.content() == null ? "" : turn.content());
                turns.add(item);
            }
        }
        state.put("recent", turns);
        List<Map<String, String>> notes = new ArrayList<>();
        if (context.corrections() != null) {
            for (CorrectionNote note : context.corrections()) {
                Map<String, String> item = new HashMap<>();
                item.put("noteId", note.noteId() == null ? "" : note.noteId());
                item.put("source", note.source() == null ? "" : note.source());
                item.put("text", note.text() == null ? "" : note.text());
                item.put("sessionId", request.sessionId() == null ? "" : request.sessionId());
                item.put("executionId", request.executionId());
                notes.add(item);
            }
        }
        state.put("corrections", notes);
        state.put("hits", new ArrayList<String>());
        state.put("toolTexts", new ArrayList<String>());
        state.put("pluginErrors", new ArrayList<String>());
        state.put("pendingTools", new ArrayList<Map<String, String>>());
        state.put("output", "");
        state.put("route", "");
        return state;
    }

    private static AgentException agent(Throwable error, String executionId) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AgentException agent) {
                return agent;
            }
            current = current.getCause();
        }
        Throwable root = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return new AgentException(
                ErrorCode.WORKFLOW,
                root.getMessage() == null ? "工作流失败" : root.getMessage(),
                executionId,
                root);
    }

    private static String chain(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            text.append(current.getClass().getName()).append(' ');
            for (StackTraceElement element : current.getStackTrace()) {
                text.append(element.getClassName()).append(' ');
            }
            current = current.getCause();
        }
        return text.toString();
    }
}
