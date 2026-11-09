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

/**
 * 用 LangGraph4j 执行或恢复工作流，并把图结果收成一次运行记录。
 */
public final class LangGraphWorkflowEngine {
    private final WorkflowGraph graph;
    private final ObjectStreamStateSerializer<WorkflowState> serializer;
    private final Map<String, BaseCheckpointSaver> savers = new HashMap<>();

    /**
     * 接上聊天、工具、插件、检索、评估和子工作流，并准备状态序列化器。任一协作者缺失时立即失败。
     *
     * @param chat 节点向模型要回复的入口
     * @param tools 节点调用工具的入口
     * @param plugins 节点各阶段调用插件的入口
     * @param retriever 检索节点使用的入口
     * @param evaluator 评估节点使用的入口
     * @param subagents 子工作流节点使用的入口
     */
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

    /**
     * 在虚拟线程上从头执行一份工作流请求。
     *
     * @param request 含定义、输入、检查点和提示的请求
     * @return 完成后给出运行结果的阶段
     */
    public CompletionStage<WorkflowRun> execute(WorkflowRequest request) {
        return VirtualIo.supply(() -> run(request, false));
    }

    /**
     * 在虚拟线程上从该执行已有的检查点继续。
     *
     * @param request 与暂停时同一执行编号的请求
     * @return 完成后给出继续运行结果的阶段
     */
    public CompletionStage<WorkflowRun> resume(WorkflowRequest request) {
        return VirtualIo.supply(() -> run(request, true));
    }

    /**
     * 打开检查点并编译图，然后从头输入或从中断处恢复。中断收成暂停，正常状态收成成功，异常收成失败。
     *
     * @param request 工作流请求
     * @param resume 为 true 时从检查点继续，而不是重新写入初始状态
     * @return 这次调用对应的运行结果
     */
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

    /**
     * 把异常收成失败的运行记录，带上错误码、已生成的图描述和异常链。
     *
     * @param request 失败所属的请求
     * @param error 执行过程中的异常
     * @param dsl 已经生成的图描述；编译前失败时为空串
     * @param saver 已经打开的检查点保存器；还没打开时为 null
     * @return 状态为失败的运行结果
     */
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
                chain(error),
                "");
    }

    /**
     * 从当前图状态取出输出、插件错误、评估结论、纠正来源和思考，收成运行记录。
     *
     * @param request 提供执行编号的请求
     * @param state 图的当前状态
     * @param status 要写入的执行状态
     * @param errorCode 错误码；成功和暂停时为空
     * @param dsl 编译后的图描述
     * @param saver 实际使用的检查点保存器
     * @param detail 附加细节；正常快照为空串
     * @return 按这些状态拼好的运行结果
     */
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
                detail,
                StateValues.text(state, "thinking"));
    }

    /**
     * 按 JDBC 地址复用或打开检查点保存器。同一地址在这个引擎里只打开一次。
     *
     * @param request 含检查点文件和 JDBC 地址的请求
     * @return 可复用的检查点保存器
     */
    private BaseCheckpointSaver saver(WorkflowRequest request) throws Exception {
        String key = CheckpointSavers.cacheKey(request.jdbcUrl());
        BaseCheckpointSaver existing = savers.get(key);
        if (existing != null) {
            return existing;
        }
        BaseCheckpointSaver created = CheckpointSavers.open(request.checkpointDatabase(), request.jdbcUrl(), serializer);
        savers.put(key, created);
        return created;
    }

    /**
     * 取工作流节点里最大的步数上限，用来估算图的递归限制。没有写上限时至少按 1 计。
     *
     * @param request 工作流请求
     * @return 节点上出现过的最大步数
     */
    private static int maxSteps(WorkflowRequest request) {
        int steps = 1;
        for (WorkflowDefinition.NodeSpec node : request.workflow().nodes()) {
            if (node.maxSteps() != null) {
                steps = Math.max(steps, node.maxSteps());
            }
        }
        return steps;
    }

    /**
     * 把请求里的身份、输入、提示、近几轮和纠正铺进图的初始状态，并把命中、工具文本和输出先放成空。
     *
     * @param request 要开始的工作流请求
     * @return 交给图的初始键值
     */
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

    /**
     * 在异常链里查找业务异常；找不到时把根因收成工作流错误。
     *
     * @param error 图执行抛出的异常
     * @param executionId 写进错误的执行编号
     * @return 可放进失败结果的业务异常
     */
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

    /**
     * 把异常链和每个异常栈上的类名拼成一段文本，供失败细节排查。
     *
     * @param error 要展开的异常
     * @return 类名和栈上类名拼成的文本
     */
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
