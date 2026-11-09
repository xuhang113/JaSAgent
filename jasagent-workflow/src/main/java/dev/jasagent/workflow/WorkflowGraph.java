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

/**
 * 把工作流定义编译成带检查点、追踪钩子和节点动作的图。
 */
final class WorkflowGraph {
    private final ChatModel chat;
    private final ToolCaller tools;
    private final PluginHost plugins;
    private final Retriever retriever;
    private final Evaluator evaluator;
    private final SubagentRunner subagents;
    private final ObjectStreamStateSerializer<WorkflowState> serializer;

    /**
     * 保存聊天、工具、插件、检索、评估、子工作流和状态序列化器，供各个节点动作使用。
     *
     * @param chat 向模型要回复的入口
     * @param tools 调用工具的入口
     * @param plugins 调用插件阶段的入口
     * @param retriever 检索入口
     * @param evaluator 评估入口
     * @param subagents 子工作流入口
     * @param serializer 图状态的序列化器
     */
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

    /**
     * 装上追踪钩子，按定义连接节点，设置递归上限和中断点，然后编译并导出图描述。
     *
     * @param definition 要编译的工作流
     * @param saver 检查点保存器
     * @return 编译后的图和它的 JSON 描述
     */
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

    /**
     * 按每个节点的步数上限估算递归限制，结果至少为 64。
     *
     * @param definition 工作流定义
     * @return 编译时使用的递归上限
     */
    private static int recursionLimit(WorkflowDefinition definition) {
        int limit = 32;
        for (NodeSpec node : definition.nodes()) {
            limit += node.maxSteps() == null ? 4 : node.maxSteps() * 4;
        }
        return Math.max(64, limit);
    }

    /**
     * 把节点分成主链和只被条件指向的分支，接上入口、主链和条件汇合。没有入口节点时抛出工作流错误。
     *
     * @param graph 正在组装的状态图
     * @param definition 工作流定义
     * @param interrupts 收集中断节点标识，方法会向其中追加
     */
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

    /**
     * 找条件节点在主链上的下一个入口。条件不在主链上时回到图的结束。
     *
     * @param spine 不作为条件目标的主链节点
     * @param condition 条件节点
     * @return 两条分支汇合后要进入的节点名或结束
     */
    private static String rejoin(List<NodeSpec> spine, NodeSpec condition) {
        for (int index = 0; index < spine.size(); index++) {
            if (spine.get(index).id().equals(condition.id())) {
                return index + 1 < spine.size() ? entry(spine.get(index + 1)) : END;
            }
        }
        return END;
    }

    /**
     * 按节点类型登记图节点。react 拆成模型和工具两个节点，中断、结束和条件先登记成透传。
     *
     * @param graph 状态图
     * @param node 节点规格
     */
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

    /**
     * 按节点类型接出边。react 在模型和工具之间循环，条件按真假进入目标，结束节点直接到图的结束。
     *
     * @param graph 状态图
     * @param node 当前节点
     * @param next 主链上的下一个入口或结束
     * @param byId 节点标识到规格的映射
     */
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

    /**
     * 用上一节点的输出和比较方式决定走真分支还是假分支。
     *
     * @param state 当前图状态
     * @param node 条件节点
     * @return 成立时为 true，否则为 false
     */
    private static String conditionRoute(WorkflowState state, NodeSpec node) {
        Object output = state.data().get("lastOutput");
        boolean matched = switch (node.compare()) {
            case "boolean" -> Boolean.TRUE.equals(output);
            case "equals" -> node.value().equals(output == null ? "" : String.valueOf(output));
            default -> compareNumber(output, node);
        };
        return matched ? "true" : "false";
    }

    /**
     * 把上一输出和期望值按大于、大于等于、小于、小于等于或相等比较。
     *
     * @param output 上一节点留下的输出
     * @param node 带比较名和期望值的条件节点
     * @return 比较成立时为 true
     */
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

    /**
     * 跑节点开始和结束阶段的插件。结束节点在最终输出还空着时，把上一输出当成最终输出。
     *
     * @param state 当前图状态
     * @param node 中断、结束或条件节点
     * @return 完成后给出状态增量的阶段
     */
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

    /**
     * 还没到步数上限时调用模型，并按工具调用或依据核对决定路由。到达上限时以最大步数错误失败。
     *
     * @param state 当前图状态
     * @param node react 节点
     * @return 完成后给出路由和输出增量的阶段
     */
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

    /**
     * 用当前状态组装消息，并向模型要一次 react 回复。调用失败收成模型错误。
     *
     * @param state 当前图状态
     * @param node react 节点，提供允许的工具名
     * @return 模型回复
     */
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

    /**
     * 有工具调用时转到工具节点。打开依据核对且数字或引号对不上时，追加一条纠正并最多重试一次。
     *
     * @param state 当前图状态
     * @param node react 节点
     * @param reply 这次模型回复
     * @param errors 本节点已经记下的插件错误
     * @param step 这个节点已经用过的模型步数
     * @return 完成后给出状态增量的阶段
     */
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

    /**
     * 路由离开本节点时补跑结束阶段，并把步数、路由、待执行工具、纠正、正文和思考写进状态增量。
     *
     * @param state 当前图状态
     * @param node react 节点
     * @param errors 插件错误
     * @param step 已用模型步数
     * @param route 下一步是 tools、retry 或 next
     * @param reply 模型回复
     * @param notes 要替换进去的纠正；没有新纠正时为 null
     * @param groundingRetries 依据核对已经安排的重试次数
     * @return 状态增量
     */
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

    /**
     * 并行执行待调用的工具，每支调用前后都跑插件阶段，然后把输出追加到工具文本。
     *
     * @param state 当前图状态，含待执行的工具
     * @param node react 节点
     * @return 含新工具文本的状态增量
     */
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

    /**
     * 用节点写明的工具名和当前输入调用一次工具，并跑开始、工具前后和结束阶段。
     *
     * @param state 当前图状态
     * @param node tool 节点
     * @return 把工具输出放进上一输出的增量
     */
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

    /**
     * 把插件节点当成透传节点，让开始和结束阶段去调用它配置的插件。
     *
     * @param state 当前图状态
     * @param node plugin 节点
     * @return 透传产生的状态增量
     */
    private CompletableFuture<Map<String, Object>> pluginNode(WorkflowState state, NodeSpec node) {
        return passThrough(state, node);
    }

    /**
     * 用当前输入做检索。节点要求必须有结果但没有命中时失败，否则把命中正文写入状态。
     *
     * @param state 当前图状态
     * @param node retrieve 节点
     * @return 含命中列表的状态增量
     */
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

    /**
     * 用节点指定的评估器检查当前输出。要求通过但没通过时失败，否则把是否通过写入状态。
     *
     * @param state 当前图状态
     * @param node evaluate 节点
     * @return 含评估结论的状态增量
     */
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

    /**
     * 跑子工作流。没有子流程标识时使用默认 react 文档，没有任务时用当前输入当唯一任务，结果同时写入最终输出。
     *
     * @param state 当前图状态
     * @param node subagents 节点
     * @return 含子流程结果的状态增量
     */
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

    /**
     * 调用工具并把失败收成工具错误。空结果收成空串。
     *
     * @param state 用来取执行编号的图状态
     * @param name 工具名
     * @param arguments 参数文本；null 按空串调用
     * @return 工具输出
     */
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

    /**
     * 按顺序调用这个节点在某一阶段配置的全部插件。
     *
     * @param state 当前图状态
     * @param node 节点规格
     * @param phase 阶段名
     * @param errors 插件失败但选择继续时，错误会追加到这里
     * @return 全部插件完成后的空阶段
     */
    private CompletableFuture<Void> phases(WorkflowState state, NodeSpec node, String phase, List<String> errors) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String pluginId : pluginsOf(node)) {
            chain = chain.thenCompose(ignored -> invokePlugin(state, node, phase, pluginId, errors));
        }
        return chain;
    }

    /**
     * 调用一个插件。节点要求失败即停时把错误抛成插件错误，否则记下阶段、插件和原因后继续。
     *
     * @param state 当前图状态
     * @param node 节点规格
     * @param phase 阶段名
     * @param pluginId 插件标识
     * @param errors 继续执行时收集错误的列表
     * @return 插件完成后的空阶段
     */
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

    /**
     * 取节点要调用的插件。列表为空时，插件节点退回使用它的单个插件标识。
     *
     * @param node 节点规格
     * @return 插件标识；没有时返回空列表
     */
    private static List<String> pluginsOf(NodeSpec node) {
        if (node.plugins() != null && !node.plugins().isEmpty()) {
            return node.plugins();
        }
        if ("plugin".equals(node.type()) && node.plugin() != null) {
            return List.of(node.plugin());
        }
        return List.of();
    }

    /**
     * 把模型回复里的工具调用收成待执行项。没有编号的调用补一个随机编号。
     *
     * @param reply 含工具调用的回复
     * @return 带编号、名字和参数的待执行项
     */
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

    /**
     * 做一条来源为依据核对的纠正，说明数字或引号片段没有出现在依据里。
     *
     * @param state 用来填写会话和执行编号的图状态
     * @return 纠正字段
     */
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

    /**
     * 标记节点已经开始，并把这次新产生的插件错误并进状态里的错误列表。
     *
     * @param state 当前图状态
     * @param node 节点规格
     * @param errors 本次新增的插件错误
     * @return 至少含开始标记和插件错误的增量
     */
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

    /**
     * 建一个本节点这次执行专用的插件错误列表。
     *
     * @return 空的可变列表
     */
    private static List<String> newErrorList() {
        return new ArrayList<>();
    }

    /**
     * 把异常收成业务异常。链上已有业务异常时原样返回，否则用给定错误码包住根因。
     *
     * @param error 原始异常
     * @param fallback 没有业务异常时使用的错误码
     * @param executionId 执行编号
     * @return 业务异常
     */
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

    /**
     * 剥掉异步包装，露出真正的失败原因。
     *
     * @param error 可能被补全或执行包装的异常
     * @return 去掉包装后的异常
     */
    private static Throwable root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 取节点在图上的入口名。react 从模型节点进入，其他节点用自己的标识。
     *
     * @param node 节点规格
     * @return 图节点名
     */
    private static String entry(NodeSpec node) {
        return "react".equals(node.type()) ? modelId(node.id()) : node.id();
    }

    /**
     * 给 react 的模型节点拼内部名字。
     *
     * @param id 工作流节点标识
     * @return 以模型后缀结尾的图节点名
     */
    private static String modelId(String id) {
        return id + "__model";
    }

    /**
     * 给 react 的工具节点拼内部名字。
     *
     * @param id 工作流节点标识
     * @return 以工具后缀结尾的图节点名
     */
    private static String toolsId(String id) {
        return id + "__tools";
    }

    /**
     * 这个 react 节点已用步数在状态里的键。
     *
     * @param id 节点标识
     * @return 步数状态键
     */
    private static String stepKey(String id) {
        return "steps." + id;
    }

    /**
     * 这个节点是否已经跑过开始阶段的状态键。
     *
     * @param id 节点标识
     * @return 开始标记的状态键
     */
    private static String startedKey(String id) {
        return "started." + id;
    }

    /**
     * 这个节点依据核对已重试次数的状态键。
     *
     * @param id 节点标识
     * @return 重试次数的状态键
     */
    private static String groundingKey(String id) {
        return "groundingRetries." + id;
    }

    /**
     * 编译结果：可执行的图和对应的 JSON 描述。
     *
     * @param graph 编译后的可执行图
     * @param dsl 图的 JSON 描述
     */
    record Compiled(CompiledGraph<WorkflowState> graph, String dsl) {
    }
}
