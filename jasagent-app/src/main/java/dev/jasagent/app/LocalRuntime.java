package dev.jasagent.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.api.auth.Catalog;
import dev.jasagent.api.auth.RuleDocument;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.auth.SkillDocument;
import dev.jasagent.api.event.TraceEvent;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.exec.ConsoleApi;
import dev.jasagent.api.exec.ExecutionApi;
import dev.jasagent.api.exec.ExecutionSnapshot;
import dev.jasagent.api.exec.ExecutionStatus;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.Embedding;
import dev.jasagent.api.llm.TokenUsage;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.persist.DataDirectory;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.schedule.ScheduleBatch;
import dev.jasagent.api.schedule.SubAgentRunner;
import dev.jasagent.api.task.AgentRole;
import dev.jasagent.api.task.TaskSnapshot;
import dev.jasagent.api.task.WorkItem;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.auth.LocalCatalogLoader;
import dev.jasagent.core.ContextInput;
import dev.jasagent.core.ContextPrepared;
import dev.jasagent.core.ContextWindow;
import dev.jasagent.core.QueueAgentScheduler;
import dev.jasagent.interrupt.CheckpointRunControl;
import dev.jasagent.interrupt.NodeTask;
import dev.jasagent.api.trace.TraceWriter;
import dev.jasagent.persist.CheckpointStores;
import dev.jasagent.persist.ExecutionRow;
import dev.jasagent.plugin.PluginToolCatalog;
import dev.jasagent.llm.ThinkingLive;
import dev.jasagent.tool.WorkspaceTools;
import dev.jasagent.workflow.ChatModel;
import dev.jasagent.workflow.Evaluator;
import dev.jasagent.workflow.LangGraphWorkflowEngine;
import dev.jasagent.workflow.PromptContext;
import dev.jasagent.workflow.WorkflowDefinition;
import dev.jasagent.workflow.WorkflowParser;
import dev.jasagent.workflow.WorkflowRequest;
import dev.jasagent.workflow.WorkflowRun;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class LocalRuntime implements ExecutionApi, ConsoleApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LocalAuthService auth;
    private final PassGate passGate;
    private final QueueAgentScheduler scheduler;
    private final double compressAt;
    private final boolean modelReady;
    private final ChatModel workflowChat;
    private final SessionModelCatalog catalog;
    private final ExecutionJournal journal;
    private final SavedTraceTracer traces = new SavedTraceTracer();
    private final Map<String, String> environment;
    private final Map<String, CheckpointRunControl> controls = new ConcurrentHashMap<>();
    private final Map<String, Exec> executions = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private PluginToolCatalog pluginTools;

    public LocalRuntime(LocalAuthService auth) {
        this(auth, Map.of());
    }

    public LocalRuntime(LocalAuthService auth, Map<String, String> configuration) {
        this(auth, configuration, SessionModelCatalog.fromEnvironment());
    }

    LocalRuntime(LocalAuthService auth, Map<String, String> configuration, SessionModelCatalog catalog) {
        this.auth = auth;
        this.catalog = catalog;
        this.passGate = PassSettings.create(configuration, userId -> auth.directories().userDirectory(userId));
        SubAgentRunner runner = item -> CompletableFuture.completedFuture(null);
        this.scheduler = new QueueAgentScheduler(configuration, runner);
        this.compressAt = ContextWindow.compressAt(configuration);
        this.modelReady = WorkflowModels.configured();
        ChatModel delegated = WorkflowModels.chat(configuration, this::modelFor);
        this.workflowChat = request -> {
            Exec current = request == null ? null : executions.get(request.executionId());
            int mark = 0;
            if (current != null) {
                synchronized (current) {
                    mark = current.thinking == null ? 0 : current.thinking.length();
                }
            }
            long started = System.nanoTime();
            int start = mark;
            return delegated.complete(request).whenComplete((reply, error) -> noteModelReply(current, reply, start, elapsed(started)));
        };
        ThinkingLive.bind(this::acceptThinking);
        this.environment = environment(configuration);
        ExecutionJournal opened = null;
        try {
            opened = ExecutionJournal.open(auth.directories().homeRoot(), environment);
        } catch (RuntimeException ignored) {
            opened = null;
        }
        this.journal = opened;
        try {
            CheckpointStores.prepare(this.environment);
        } catch (RuntimeException ignored) {
            // 健康检查会把连不上的检查点报成 storage down。
        }
    }

    public boolean storageReachable() {
        return journal != null && journal.reachable() && CheckpointStores.reachable(environment);
    }

    List<String> observedChildTraceIds() {
        return traces.childTraceIds();
    }

    public void close() {
        controls.clear();
        workers.shutdownNow();
    }

    @Override
    public Map<String, Object> submit(String userId, String token, String workflowId, String input) {
        return submit(userId, token, workflowId, input, null);
    }

    private Map<String, Object> submit(String userId, String token, String workflowId, String input, String sessionId) {
        return submit(userId, token, workflowId, input, sessionId, "", "");
    }

    private Map<String, Object> submit(
            String userId,
            String token,
            String workflowId,
            String input,
            String sessionId,
            String provider,
            String model) {
        requireJournal();
        Session session = auth.authenticate(token).toCompletableFuture().join();
        if (session.workspace() == null || session.workspace().isBlank()) {
            throw new AgentException(ErrorCode.WORKFLOW, "未选择工作区");
        }
        String id = UUID.randomUUID().toString();
        String workspace = Path.of(session.workspace()).toAbsolutePath().normalize().toString();
        Exec exec = new Exec(id, userId, workspace, session.token());
        String sessionKey = sessionId == null || sessionId.isBlank() ? id : sessionId;
        exec.sessionId = sessionKey;
        exec.selectedProvider = provider == null ? "" : provider;
        exec.selectedModel = model == null ? "" : model;
        if (!exec.selectedModel.isBlank()) {
            exec.append("model " + exec.selectedProvider + " " + exec.selectedModel);
        }
        exec.traceId = hex(32);
        exec.rootSpanId = hex(16);
        traces.assign(id, exec.traceId, exec.rootSpanId);
        executions.put(id, exec);
        exec.append("accepted " + id);
        exec.append("user " + userId);
        exec.append("workspace " + workspace);
        rememberCapabilities(exec, loadDefinition(workspace, workflowId));
        String text = input == null ? "" : input;
        exec.pendingWorkflow = workflowId;
        exec.pendingInput = text;
        scheduler.enqueue(new WorkItem(id, id, sessionKey, workflowId, text, workflowId, text))
                .toCompletableFuture()
                .join();
        ScheduleBatch batch = scheduler.scheduleOnce().toCompletableFuture().join();
        for (TaskSnapshot snap : batch.assigned()) {
            if (snap.item() == null || !sessionKey.equals(snap.item().sessionId()) || snap.role() != AgentRole.SUB) {
                continue;
            }
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("agentId", snap.agentId());
            agent.put("role", snap.role().name());
            agent.put("executionId", snap.item().executionId());
            agent.put("status", snap.status());
            exec.agents.add(agent);
            exec.queue.add(snap.agentId() + ": " + workflowId);
        }
        if (exec.queue.isEmpty()) {
            exec.queue.add("main: " + workflowId);
        }
        journal.insert(new ExecutionRow(id, userId, sessionKey, workspace, "ACCEPTED", exec.traceId, exec.rootSpanId));
        save(exec, false);
        workers.execute(() -> start(exec));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", id);
        body.put("status", "ACCEPTED");
        return body;
    }

    @Override
    public List<Map<String, Object>> list(String userId) {
        requireJournal();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExecutionRow row : journal.list(userId)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("executionId", row.executionId());
            summary.put("status", row.status());
            rows.add(summary);
        }
        return rows;
    }

    @Override
    public Map<String, Object> detail(String userId, String executionId) {
        return view(owned(userId, executionId));
    }

    @Override
    public String logs(String userId, String executionId) {
        return owned(userId, executionId).logs.toString();
    }

    @Override
    public Map<String, Object> interrupt(String userId, String executionId) {
        Exec exec = owned(userId, executionId);
        synchronized (exec) {
            exec.append("interrupt");
            save(exec, false);
        }
        try {
            ExecutionSnapshot snapshot = controlFor(exec).interrupt(exec.executionId, exec.userId).toCompletableFuture().join();
            applySnapshot(exec, snapshot);
        } catch (RuntimeException ex) {
            synchronized (exec) {
                if (!terminalStatus(exec.status)) {
                    finishSuspended(exec, exec.output);
                    save(exec, true);
                }
            }
        }
        return view(owned(userId, executionId));
    }

    @Override
    public Map<String, Object> resume(String userId, String executionId) {
        Exec exec = owned(userId, executionId);
        synchronized (exec) {
            if (!"SUSPENDED".equals(exec.status)) {
                return view(exec);
            }
        }
        try {
            CheckpointRunControl control = controlFor(exec);
            synchronized (exec) {
                if (!"SUSPENDED".equals(exec.status)) {
                    return view(exec);
                }
                exec.resuming = true;
                exec.suspended = false;
                exec.status = "RUNNING";
                exec.errorCode = "";
                exec.append("resume");
                save(exec, true);
            }
            ExecutionSnapshot snapshot = control.resume(exec.executionId, exec.userId).toCompletableFuture().join();
            applySnapshot(exec, snapshot);
        } catch (RuntimeException ex) {
            synchronized (exec) {
                exec.suspended = true;
                exec.status = "SUSPENDED";
                exec.errorCode = offlineCode(ex);
                exec.append("resume failed " + exec.errorCode);
                save(exec, true);
            }
        }
        return view(owned(userId, executionId));
    }

    @Override
    public Map<String, Object> search(String userId, String token, String query, List<String> scopes) {
        auth.authenticate(token).toCompletableFuture().join();
        return retrieval(query);
    }

    private void start(Exec exec) {
        try {
            ExecutionSnapshot snapshot = controlFor(exec)
                    .start(exec.executionId, exec.userId, exec.sessionId, exec.workspace)
                    .toCompletableFuture()
                    .join();
            applySnapshot(exec, snapshot);
        } catch (RuntimeException ex) {
            synchronized (exec) {
                recordFailure(exec, ex);
                save(exec, true);
            }
        }
    }

    private CompletionStage<String> runNode(String executionId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        workers.execute(() -> runWorkflow(executionId, future));
        return future;
    }

    private void runWorkflow(String executionId, CompletableFuture<String> future) {
        Exec exec = requireExec(executionId);
        try {
            executeWorkflow(exec);
        } catch (RuntimeException ex) {
            synchronized (exec) {
                recordFailure(exec, ex);
                save(exec, true);
            }
        }
        if (future.isDone()) {
            return;
        }
        synchronized (exec) {
            save(exec, false);
            boolean hold = exec.suspended || pending(exec);
            if (!"FAILED".equals(exec.status) && hold) {
                future.completeExceptionally(new CancellationException("suspended"));
                hang(exec);
                return;
            }
            if (!"SUCCEEDED".equals(exec.status)) {
                future.completeExceptionally(failure(exec));
                return;
            }
            future.complete(exec.output == null ? "" : exec.output);
        }
    }

    private void executeWorkflow(Exec exec) {
        Path database = checkpoint(exec);
        synchronized (exec) {
            exec.pendingWorkflow = exec.pendingWorkflow == null ? "" : exec.pendingWorkflow;
            exec.status = "RUNNING";
            exec.suspended = false;
            save(exec, true);
        }
        WorkflowDefinition definition = loadDefinition(exec.workspace, exec.pendingWorkflow);
        boolean usesModel = false;
        for (WorkflowDefinition.NodeSpec node : definition.nodes()) {
            if ("react".equals(node.type())) {
                usesModel = true;
                break;
            }
        }
        exec.modelNode = usesModel;
        List<Map<String, Object>> rules = new ArrayList<>();
        List<Map<String, Object>> skills = new ArrayList<>();
        try {
            Catalog catalog = catalog(exec.userId, exec.token);
            for (RuleDocument rule : catalog.rules()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rule.id() == null ? "" : rule.id());
                row.put("description", rule.description() == null ? "" : rule.description());
                rules.add(row);
            }
            for (SkillDocument skill : catalog.skills()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", skill.name() == null ? "" : skill.name());
                row.put("description", skill.description() == null ? "" : skill.description());
                skills.add(row);
            }
        } catch (RuntimeException ignored) {
            // 目录读不到时时间线仍保留 system 和 human。
        }
        synchronized (exec) {
            if (!hasSystem(exec)) {
                recordOpening(exec, rules, skills, exec.pendingInput);
            }
        }
        LangGraphWorkflowEngine engine = new LangGraphWorkflowEngine(
                workflowChat,
                (id, name, arguments) -> callTool(exec, name, arguments),
                call -> holdPlugin(exec, call.pluginId()),
                query -> CompletableFuture.completedFuture(List.of()),
                query -> CompletableFuture.completedFuture(new Evaluator.EvaluationOutcome(true, "tool.success.rate")),
                call -> CompletableFuture.completedFuture(""));
        List<String> ruleLines = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            ruleLines.add(rule.get("id") + " " + rule.get("description"));
        }
        List<String> skillLines = new ArrayList<>();
        for (Map<String, Object> skill : skills) {
            skillLines.add(skill.get("name") + " " + skill.get("description"));
        }
        WorkflowRequest request = new WorkflowRequest(
                exec.executionId,
                exec.sessionId,
                exec.userId,
                database,
                exec.pendingInput == null ? "" : exec.pendingInput,
                definition,
                new PromptContext("assistant", ruleLines, skillLines, List.of(), "", List.of(), List.of()),
                CheckpointStores.workflowJdbc(environment, database));
        WorkflowRun result = engine.execute(request).toCompletableFuture().join();
        synchronized (exec) {
            boolean hold = exec.suspended || pending(exec);
            if (hold) {
                if (result.output() != null && !result.output().isBlank()) {
                    exec.output = result.output();
                }
                rememberThinking(exec, result);
                recordAi(exec, exec.output);
                finishSuspended(exec, exec.output);
                return;
            }
            boolean offline = !modelReady && ("LLM".equals(result.errorCode()) || (usesModel && "SUCCEEDED".equals(result.status())));
            if (offline) {
                exec.output = "";
                exec.thinking = "";
                exec.errorCode = "OFFLINE";
                exec.status = "FAILED";
                recordAi(exec, "模型未配置");
                exec.append("terminal FAILED OFFLINE");
                return;
            }
            exec.output = result.output() == null ? "" : result.output();
            rememberThinking(exec, result);
            exec.evaluations.add("completion.nonempty:" + !exec.output.isBlank());
            exec.errorCode = result.errorCode() == null ? "" : result.errorCode();
            recordAi(exec, exec.output);
            exec.status = "FAILED".equals(result.status()) ? "FAILED" : "SUCCEEDED";
            exec.append("terminal " + exec.status);
            if ("SUCCEEDED".equals(exec.status)) {
                exec.queue.clear();
                exec.queue.add("sub-1: done");
            }
        }
    }

    private void recordOpening(Exec exec, List<Map<String, Object>> rules, List<Map<String, Object>> skills, String input) {
        StringBuilder content = new StringBuilder();
        content.append("role: assistant\n");
        content.append("rules:\n");
        for (Map<String, Object> rule : rules) {
            content.append(rule.get("id")).append(' ').append(rule.get("description")).append('\n');
        }
        content.append("skills:\n");
        for (Map<String, Object> skill : skills) {
            content.append(skill.get("name")).append(' ').append(skill.get("description")).append('\n');
        }
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("type", "system");
        system.put("content", content.toString());
        system.put("rules", List.copyOf(rules));
        system.put("skills", List.copyOf(skills));
        Map<String, Object> human = new LinkedHashMap<>();
        human.put("type", "human");
        human.put("content", input == null ? "" : input);
        synchronized (exec.timeline) {
            exec.timeline.add(system);
            exec.timeline.add(human);
        }
    }

    private static void rememberThinking(Exec exec, WorkflowRun result) {
        if (result == null || result.thinking() == null || result.thinking().isBlank()) {
            return;
        }
        String next = result.thinking().trim();
        String current = exec.thinking == null ? "" : exec.thinking;
        if (current.isBlank()) {
            exec.thinking = next;
            return;
        }
        if (!current.contains(next)) {
            exec.thinking = current.stripTrailing() + "\n\n" + next;
        }
    }

    private void acceptThinking(String executionId, String delta) {
        if (executionId == null || delta == null || delta.isEmpty()) {
            return;
        }
        Exec exec = executions.get(executionId);
        if (exec == null) {
            return;
        }
        synchronized (exec) {
            exec.thinking = exec.thinking == null || exec.thinking.isEmpty() ? delta : exec.thinking + delta;
        }
    }

    private void noteModelReply(Exec exec, ChatModel.ModelReply reply, int mark, long elapsedMs) {
        if (exec == null || reply == null || reply.thinking() == null || reply.thinking().isBlank()) {
            return;
        }
        String thought = reply.thinking().trim();
        synchronized (exec) {
            String current = exec.thinking == null ? "" : exec.thinking;
            int cut = Math.min(Math.max(mark, 0), current.length());
            String prior = current.substring(0, cut);
            String streamed = current.substring(cut);
            if (streamed.equals(thought) || streamed.strip().equals(thought)) {
                exec.thinking = joinThought(prior, thought);
            } else if (!current.contains(thought)) {
                exec.thinking = joinThought(current, thought);
            }
            recordThinking(exec, thought, elapsedMs);
        }
    }

    private static String joinThought(String prior, String thought) {
        String head = prior == null ? "" : prior.stripTrailing();
        if (head.isEmpty()) {
            return thought;
        }
        return head + "\n\n" + thought;
    }

    private static long elapsed(long started) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private void recordAi(Exec exec, String content) {
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("type", "ai");
        ai.put("content", content == null ? "" : content);
        ai.put("tool_calls", List.of());
        if (exec.thinking != null && !exec.thinking.isBlank()) {
            ai.put("thinking", exec.thinking);
        }
        synchronized (exec.timeline) {
            exec.timeline.add(ai);
        }
    }

    private void recordThinking(Exec exec, String thought, long elapsedMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "thinking");
        row.put("content", thought == null ? "" : thought);
        row.put("elapsedMs", elapsedMs);
        synchronized (exec.timeline) {
            exec.timeline.add(row);
        }
    }

    private void recordTool(Exec exec, String name, String result, long elapsedMs, String arguments) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "tool");
        tool.put("name", name);
        tool.put("tool_call_id", name + "-" + exec.toolSequence.incrementAndGet());
        tool.put("content", result == null ? "" : result);
        tool.put("arguments", arguments == null ? "" : arguments);
        tool.put("elapsedMs", elapsedMs);
        synchronized (exec.timeline) {
            exec.timeline.add(tool);
        }
    }

    private static Path checkpoint(Exec exec) {
        try {
            Path directory = DataDirectory.of(Path.of(exec.workspace));
            Files.createDirectories(directory);
            exec.runs += 1;
            return directory.resolve(exec.executionId + "-" + exec.runs + ".db");
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点存储器无法打开", exec.executionId, ex);
        }
    }

    private void recordFailure(Exec exec, RuntimeException ex) {
        String code = offlineCode(ex);
        if (exec.suspended && !"PERSIST".equals(code) && !"OFFLINE".equals(code)) {
            finishSuspended(exec, exec.output);
            return;
        }
        exec.suspended = false;
        exec.status = "FAILED";
        exec.errorCode = code;
        recordAi(exec, "OFFLINE".equals(code) ? "模型未配置" : exec.output);
        exec.append("terminal FAILED " + exec.errorCode);
    }

    private void finishSuspended(Exec exec, String output) {
        exec.suspended = true;
        exec.status = "SUSPENDED";
        if (output != null && !output.isBlank()) {
            exec.output = output;
        }
    }

    private static boolean pending(Exec exec) {
        for (Map<String, Object> pass : exec.passes) {
            if (PassDecision.PENDING.name().equals(String.valueOf(pass.get("decision")))
                    && Boolean.TRUE.equals(pass.get("blocked"))) {
                return true;
            }
        }
        return false;
    }

    private CompletionStage<Void> holdPlugin(Exec exec, String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, Object> recorded = findPass(exec, pluginId, "plugin");
        if (recorded == null) {
            recorded = addPass(exec, pluginId, "plugin");
        }
        String decision = String.valueOf(recorded.get("decision"));
        exec.append("pass " + pluginId + " " + decision);
        if (PassDecision.PENDING.name().equals(decision)) {
            exec.suspended = true;
            exec.status = "SUSPENDED";
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<String> callTool(Exec exec, String name, String arguments) {
        long started = System.nanoTime();
        if (!"clock".equals(name)) {
            Map<String, Object> recorded = findPass(exec, name, "tool");
            if (recorded == null) {
                recorded = addPass(exec, name, "tool");
            }
            recorded.put("requested", Boolean.TRUE);
            exec.append("pass " + name + " " + recorded.get("decision"));
            String decision = String.valueOf(recorded.get("decision"));
            if (PassDecision.DENY.name().equals(decision)) {
                recordTool(exec, name, "denied", elapsed(started), arguments);
                return CompletableFuture.completedFuture("denied");
            }
            boolean waiting = PassDecision.PENDING.name().equals(decision);
            if (waiting && !WorkspaceTools.runsWithoutApproval(name, arguments)) {
                recorded.put("blocked", Boolean.TRUE);
                if (!(exec.resuming && !exec.modelNode)) {
                    exec.suspended = true;
                    exec.status = "SUSPENDED";
                }
                recordTool(exec, name, "approval-required", elapsed(started), arguments);
                return CompletableFuture.completedFuture("approval-required");
            }
        }
        String result;
        if ("clock".equals(name)) {
            result = Instant.now().toString();
        } else if ("rag.search".equals(name)) {
            exec.retrievals.add(retrieval(arguments));
            result = "retrieved";
        } else if (WorkspaceTools.supports(name)) {
            result = WorkspaceTools.execute(Path.of(exec.workspace), name, arguments);
        } else if (pluginNames().contains(name)) {
            result = callPlugin(exec, name, arguments);
        } else {
            result = "未知工具 " + name;
        }
        recordTool(exec, name, result, elapsed(started), arguments);
        return CompletableFuture.completedFuture(result);
    }

    private String callPlugin(Exec exec, String name, String arguments) {
        try {
            String result = pluginTools().require(name).call(new ToolCall(
                    exec.userId,
                    exec.executionId,
                    auth.directories().userDirectory(exec.userId),
                    Path.of(exec.workspace).resolve("jasagent"),
                    name,
                    argumentMap(arguments))).toCompletableFuture().join();
            CallStats.load().recordSuccess(name);
            return result == null ? "" : result;
        } catch (RuntimeException ex) {
            try {
                CallStats.load().recordFailure(name);
            } catch (RuntimeException ignored) {
                // 统计写不进去时仍把工具失败留在时间线。
            }
            Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
            return cause.getMessage() == null ? "tool failed" : cause.getMessage();
        }
    }

    private static Map<String, Object> retrieval(String arguments) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("sourceScope", "workspace");
        hit.put("uri", "rag.search");
        hit.put("text", arguments == null ? "" : arguments);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "");
        body.put("hits", List.of(hit));
        return body;
    }

    private Exec owned(String userId, String executionId) {
        Exec exec = executions.get(executionId);
        if (exec == null) {
            exec = load(executionId);
            if (exec != null) {
                executions.putIfAbsent(executionId, exec);
                exec = executions.get(executionId);
            }
        }
        if (exec == null || !userId.equals(exec.userId)) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的执行");
        }
        overlay(exec);
        return exec;
    }

    private static Map<String, Object> summary(Exec exec) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("executionId", exec.executionId);
        row.put("status", exec.status);
        return row;
    }

    private Map<String, Object> view(Exec exec) {
        synchronized (exec) {
            Map<String, Object> row = summary(exec);
            row.put("output", exec.output);
            row.put("thinking", exec.thinking == null ? "" : exec.thinking);
            row.put("errorCode", exec.errorCode);
            row.put("queue", List.copyOf(exec.queue));
            row.put("logs", exec.logs.toString());
            row.put("retrievals", copyMaps(exec.retrievals));
            row.put("passes", copyMaps(exec.passes));
            row.put("evaluations", List.copyOf(exec.evaluations));
            row.put("tools", List.copyOf(exec.tools));
            row.put("plugins", List.copyOf(exec.plugins));
            row.put("traceId", exec.traceId);
            return row;
        }
    }

    @Override
    public List<Map<String, Object>> workflows(String userId, String token) {
        Session session = auth.authenticate(token).toCompletableFuture().join();
        if (!userId.equals(session.login())) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的执行");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add("answer-with-tool");
        if (session.workspace() != null && !session.workspace().isBlank()) {
            Path root = Path.of(session.workspace()).resolve("jasagent").resolve("workflows");
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.list(root)) {
                    files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(path -> {
                                String name = path.getFileName().toString();
                                ids.add(name.substring(0, name.length() - 5));
                            });
                } catch (Exception ex) {
                    throw new AgentException(ErrorCode.WORKFLOW, "工作流无法列出", null, ex);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : ids) {
            rows.add(Map.of("id", id));
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> skills(String userId, String token) {
        Catalog catalog = catalog(userId, token);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SkillDocument skill : catalog.skills()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", skill.name());
            row.put("description", skill.description());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> sessions(String userId) {
        Path root = conversations(userId);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Map<String, Object> stored = readJson(file);
                if (!userId.equals(stored.get("userId"))) {
                    continue;
                }
                rows.add(sessionSummary(stored));
            }
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.INTERNAL, "会话无法列出", null, ex);
        }
        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("createdAt"))));
        return rows;
    }

    @Override
    public Map<String, Object> createSession(String userId, String title) {
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("sessionId", sessionId);
        stored.put("userId", userId);
        String requested = title == null ? "" : title.trim();
        boolean automatic = automaticTitle(requested);
        stored.put("title", automatic ? "新会话" : limitTitle(requested, 80));
        stored.put("titleCustom", !automatic);
        stored.put("model", SessionModelCatalog.DEFAULT_MODEL);
        stored.put("modelProvider", SessionModelCatalog.DEEPSEEK);
        stored.put("createdAt", Instant.now().toString());
        stored.put("messages", new ArrayList<>());
        writeJson(conversationFile(userId, sessionId), stored);
        return sessionSummary(stored);
    }

    @Override
    public Map<String, Object> renameSession(String userId, String sessionId, String title) {
        Path file = conversationFile(userId, sessionId);
        Map<String, Object> stored = readOwned(userId, file);
        String cleaned = title == null ? "" : title.trim();
        if (cleaned.isEmpty()) {
            throw new AgentException(ErrorCode.WORKFLOW, "会话标题不能为空");
        }
        stored.put("title", limitTitle(cleaned, 80));
        stored.put("titleCustom", true);
        writeJson(file, stored);
        return sessionSummary(stored);
    }

    @Override
    public Map<String, Object> updateModel(String userId, String sessionId, String model, String provider) {
        Path file = conversationFile(userId, sessionId);
        Map<String, Object> stored = readOwned(userId, file);
        SessionModelCatalog.ModelChoice choice = catalog.require(model, provider);
        stored.put("model", choice.id());
        stored.put("modelProvider", choice.provider());
        writeJson(file, stored);
        return sessionSummary(stored);
    }

    @Override
    public Map<String, Object> models(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SessionModelCatalog.ModelChoice choice : catalog.available()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", choice.id());
            row.put("provider", choice.provider());
            row.put("label", choice.label());
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultModel", SessionModelCatalog.DEFAULT_MODEL);
        body.put("models", rows);
        return body;
    }

    @Override
    public Map<String, Object> deleteSession(String userId, String sessionId) {
        Path file = conversationFile(userId, sessionId);
        Map<String, Object> stored = readOwned(userId, file);
        try {
            Files.deleteIfExists(file);
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.INTERNAL, "会话无法删除", null, ex);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", stored.get("sessionId"));
        row.put("deleted", true);
        return row;
    }

    @Override
    public Map<String, Object> session(String userId, String sessionId) {
        Map<String, Object> stored = readOwned(userId, conversationFile(userId, sessionId));
        Map<String, Object> body = new LinkedHashMap<>(stored);
        body.put("model", SessionModelCatalog.model(stored));
        body.put("modelProvider", SessionModelCatalog.provider(stored));
        body.put("corrections", corrections(userId, sessionId));
        body.putIfAbsent("agents", List.of());
        return body;
    }

    @Override
    public Map<String, Object> appendMessage(String userId, String token, String sessionId, String text, String workflowId) {
        return appendMessage(userId, token, sessionId, text, workflowId, null, null);
    }

    @Override
    public Map<String, Object> appendMessage(
            String userId,
            String token,
            String sessionId,
            String text,
            String workflowId,
            String model,
            String provider) {
        if (text == null || text.isBlank()) {
            throw new AgentException(ErrorCode.WORKFLOW, "消息不能为空");
        }
        Path file = conversationFile(userId, sessionId);
        Map<String, Object> stored = readOwned(userId, file);
        if (model != null && !model.isBlank()) {
            SessionModelCatalog.ModelChoice choice = catalog.require(model, provider);
            stored.put("model", choice.id());
            stored.put("modelProvider", choice.provider());
            writeJson(file, stored);
        }
        boolean first = messages(stored).isEmpty();
        String chosen = workflowId == null || workflowId.isBlank() ? "answer-with-tool" : workflowId;
        Map<String, Object> created = submit(
                userId,
                token,
                chosen,
                text,
                sessionId,
                SessionModelCatalog.provider(stored),
                SessionModelCatalog.model(stored));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("text", text);
        message.put("executionId", created.get("executionId"));
        message.put("createdAt", Instant.now().toString());
        messages(stored).add(message);
        Exec exec = owned(userId, String.valueOf(created.get("executionId")));
        for (Map<String, Object> agent : exec.agents) {
            agents(stored).add(new LinkedHashMap<>(agent));
        }
        if (first && !titleEdited(stored)) {
            String summary = summarizeTitle(text);
            if (!summary.isEmpty()) {
                stored.put("title", summary);
            }
        }
        writeJson(file, stored);
        return message;
    }

    @Override
    public Map<String, Object> timeline(String userId, String sessionId, String executionId) {
        Map<String, Object> stored = readOwned(userId, conversationFile(userId, sessionId));
        String chosen = executionId;
        if (chosen == null || chosen.isBlank()) {
            List<Map<String, Object>> history = messages(stored);
            for (int index = history.size() - 1; index >= 0; index--) {
                Object value = history.get(index).get("executionId");
                if (value != null && !String.valueOf(value).isBlank()) {
                    chosen = String.valueOf(value);
                    break;
                }
            }
        }
        if (chosen == null || chosen.isBlank()) {
            throw new AgentException(ErrorCode.WORKFLOW, "这条会话还没有执行");
        }
        boolean linked = false;
        for (Map<String, Object> message : messages(stored)) {
            if (chosen.equals(String.valueOf(message.get("executionId")))) {
                linked = true;
                break;
            }
        }
        if (!linked) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的会话");
        }
        Exec exec = owned(userId, chosen);
        List<Map<String, Object>> messages = new ArrayList<>();
        synchronized (exec.timeline) {
            for (Map<String, Object> message : exec.timeline) {
                messages.add(new LinkedHashMap<>(message));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", stored.get("sessionId"));
        body.put("executionId", chosen);
        body.put("messages", messages);
        return body;
    }

    @Override
    public Map<String, Object> context(String userId, String token, String sessionId) {
        Map<String, Object> stored = readOwned(userId, conversationFile(userId, sessionId));
        Catalog catalog = catalog(userId, token);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (RuleDocument rule : catalog.rules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rule.id());
            row.put("description", rule.description());
            rules.add(row);
        }
        List<dev.jasagent.api.llm.ChatMessage> turns = new ArrayList<>();
        for (Map<String, Object> message : messages(stored)) {
            String text = message.get("text") == null ? "" : String.valueOf(message.get("text"));
            String role = "assistant".equals(message.get("role")) ? "assistant" : "user";
            turns.add(dev.jasagent.api.llm.ChatMessage.text(role, text));
        }
        ContextWindow window = new ContextWindow(new OfflineChatModel(), compressAt);
        ContextInput input = new ContextInput(
                sessionId,
                "assistant",
                "",
                List.of(),
                "",
                List.of(),
                turns,
                "",
                ContextWindow.DEFAULT_BUDGET_TOKENS);
        try {
            window.prepare(input).toCompletableFuture().join();
        } catch (RuntimeException ignored) {
            // 压缩没完成时不把半截摘要当成上下文。
        }
        String summary = "";
        ContextPrepared published = window.published();
        if (published != null && published.summary() != null) {
            summary = published.summary();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", stored.get("sessionId"));
        body.put("title", stored.get("title"));
        body.put("rules", rules);
        body.put("skills", skills(userId, token));
        body.put("corrections", corrections(userId, sessionId));
        body.put("messages", messages(stored));
        body.put("budgetTokens", ContextWindow.DEFAULT_BUDGET_TOKENS);
        body.put("compressAt", compressAt);
        body.put("occupancy", ContextWindow.estimatedTokens(input) / (double) ContextWindow.DEFAULT_BUDGET_TOKENS);
        body.put("summary", summary);
        body.put("compressing", window.compressing());
        return body;
    }

    @Override
    public Map<String, Object> addCorrection(String userId, String sessionId, String text, String executionId) {
        readOwned(userId, conversationFile(userId, sessionId));
        if (text == null || text.isBlank()) {
            throw new AgentException(ErrorCode.WORKFLOW, "纠正 note 不能为空");
        }
        String noteId = UUID.randomUUID().toString();
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("noteId", noteId);
        note.put("sessionId", sessionId);
        note.put("executionId", executionId == null ? "" : executionId);
        note.put("createdAt", Instant.now().toString());
        note.put("source", "USER");
        note.put("text", text);
        writeJson(correctionFile(userId, sessionId, noteId), note);
        return note;
    }

    @Override
    public List<Map<String, Object>> corrections(String userId, String sessionId) {
        readOwned(userId, conversationFile(userId, sessionId));
        Path root = auth.directories().userDirectory(userId).resolve("corrections").resolve(sessionId);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Map<String, Object>> notes = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                notes.add(readJson(file));
            }
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.INTERNAL, "纠正 note 无法读取", null, ex);
        }
        return notes;
    }

    @Override
    public Map<String, Object> decidePass(String userId, String executionId, String passId, String decision) {
        if (!"ALLOW".equals(decision) && !"DENY".equals(decision)) {
            throw new AgentException(ErrorCode.WORKFLOW, "决定只允许 ALLOW 或 DENY", executionId, null);
        }
        Exec exec = owned(userId, executionId);
        for (Map<String, Object> pass : exec.passes) {
            if (passId.equals(pass.get("passId"))) {
                pass.put("decision", decision);
                pass.put("reason", "ALLOW".equals(decision) ? "approved" : "denied");
                boolean resume = "ALLOW".equals(decision) && exec.pendingWorkflow != null && (exec.suspended || "SUSPENDED".equals(exec.status));
                if (resume) {
                    exec.append("resume");
                }
                save(exec, true);
                if (resume) {
                    workers.execute(() -> continueRun(exec));
                }
                return view(exec);
            }
        }
        throw new AgentException(ErrorCode.WORKFLOW, "通行证不存在", executionId, null);
    }

    private void rememberCapabilities(Exec exec, WorkflowDefinition document) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        LinkedHashSet<String> plugins = new LinkedHashSet<>();
        for (WorkflowDefinition.NodeSpec node : document.nodes()) {
            if (node.tools() != null) {
                tools.addAll(node.tools());
            }
            if (node.tool() != null && !node.tool().isBlank()) {
                tools.add(node.tool());
            }
            if (node.plugins() != null) {
                plugins.addAll(node.plugins());
            }
            if (Boolean.TRUE.equals(node.grounding())) {
                plugins.add("grounding.default");
            }
        }
        exec.tools.addAll(tools);
        exec.plugins.addAll(plugins);
        for (String tool : tools) {
            if (!"clock".equals(tool) && findPass(exec, tool, "tool") == null) {
                addPass(exec, tool, "tool");
            }
        }
        for (String plugin : plugins) {
            if (findPass(exec, plugin, "plugin") == null) {
                addPass(exec, plugin, "plugin");
            }
        }
    }

    private Map<String, Object> addPass(Exec exec, String name, String kind) {
        boolean tool = "tool".equals(kind);
        var pass = passGate.acquire(new PassRequest(
                exec.userId,
                exec.executionId,
                tool ? name : null,
                tool ? null : name,
                "")).toCompletableFuture().join();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("passId", UUID.randomUUID().toString());
        row.put("name", name);
        row.put("kind", kind);
        row.put("decision", pass.decision().name());
        row.put("reason", pass.reason());
        exec.passes.add(row);
        return row;
    }

    private static Map<String, Object> findPass(Exec exec, String name, String kind) {
        for (Map<String, Object> pass : exec.passes) {
            if (name.equals(pass.get("name")) && kind.equals(pass.get("kind"))) {
                return pass;
            }
        }
        return null;
    }

    private static WorkflowDefinition loadDefinition(String workspace, String workflowId) {
        try {
            String json = WorkflowParser.exampleAnswerWithTool();
            if (workflowId != null && !workflowId.isBlank() && !"answer-with-tool".equals(workflowId)) {
                Path file = Path.of(workspace == null ? "" : workspace).resolve("jasagent").resolve("workflows").resolve(workflowId + ".json");
                if (!Files.isRegularFile(file)) {
                    throw new AgentException(ErrorCode.WORKFLOW, "工作流无法装载");
                }
                json = Files.readString(file);
            }
            return WorkflowParser.parse(json);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.WORKFLOW, "工作流无法装载", null, ex);
        }
    }

    private Catalog catalog(String userId, String token) {
        Session session = auth.authenticate(token).toCompletableFuture().join();
        if (!userId.equals(session.login())) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的目录");
        }
        Path userDirectory = auth.directories().userDirectory(userId);
        Path workspaceDirectory = session.workspace() == null || session.workspace().isBlank()
                ? null
                : Path.of(session.workspace()).resolve("jasagent");
        return new LocalCatalogLoader().load(workspaceDirectory, userDirectory).toCompletableFuture().join();
    }

    private Path conversations(String userId) {
        return auth.directories().userDirectory(userId).resolve("conversations");
    }

    private Path conversationFile(String userId, String sessionId) {
        return conversations(userId).resolve(safeId(sessionId) + ".json");
    }

    private Path correctionFile(String userId, String sessionId, String noteId) {
        return auth.directories().userDirectory(userId).resolve("corrections").resolve(safeId(sessionId)).resolve(safeId(noteId) + ".json");
    }

    private Map<String, Object> readOwned(String userId, Path file) {
        Map<String, Object> stored = readJson(file);
        if (!userId.equals(String.valueOf(stored.get("userId")))) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的会话");
        }
        return stored;
    }

    private static Map<String, Object> readJson(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的会话");
            }
            return JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.INTERNAL, "会话无法读取", null, ex);
        }
    }

    private static void writeJson(Path file, Map<String, Object> body) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.INTERNAL, "会话无法写入", null, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> agents(Map<String, Object> stored) {
        Object value = stored.get("agents");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        stored.put("agents", created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<String, Object> stored) {
        Object value = stored.get("messages");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        stored.put("messages", created);
        return created;
    }

    private static Map<String, Object> sessionSummary(Map<String, Object> stored) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", stored.get("sessionId"));
        row.put("title", stored.get("title"));
        row.put("model", SessionModelCatalog.model(stored));
        row.put("modelProvider", SessionModelCatalog.provider(stored));
        row.put("createdAt", stored.get("createdAt"));
        return row;
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的会话");
        }
        return id;
    }

    private void requireJournal() {
        if (journal == null) {
            throw new AgentException(ErrorCode.PERSIST, "存储不可连接");
        }
    }

    private static Map<String, String> environment(Map<String, String> configuration) {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (configuration == null) {
            return env;
        }
        for (String key : List.of(
                "JASAGENT_JDBC_URL",
                "JASAGENT_JDBC_USER",
                "JASAGENT_JDBC_PASSWORD",
                "JASAGENT_CHECKPOINT",
                "JASAGENT_CHECKPOINT_JDBC_URL",
                "JASAGENT_CHECKPOINT_JDBC_USER",
                "JASAGENT_CHECKPOINT_JDBC_PASSWORD")) {
            String value = configuration.get(key);
            if (value != null && !value.isBlank()) {
                env.put(key, value);
            }
        }
        return env;
    }

    private static boolean automaticTitle(String title) {
        if (title == null || title.isBlank()) {
            return true;
        }
        String trimmed = title.trim();
        return "新会话".equals(trimmed) || trimmed.matches("新会话\\s+\\d+") || trimmed.matches("会话\\s*\\d+");
    }

    private static boolean titleEdited(Map<String, Object> stored) {
        Object flag = stored.get("titleCustom");
        if (flag instanceof Boolean value) {
            return value;
        }
        Object title = stored.get("title");
        return !automaticTitle(title == null ? "" : String.valueOf(title));
    }

    private static String summarizeTitle(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return limitTitle(normalized, 40);
    }

    private static String limitTitle(String title, int max) {
        if (title.length() <= max) {
            return title;
        }
        return title.substring(0, max);
    }

    private WorkflowModels.Choice modelFor(String executionId) {
        Exec exec = executions.get(executionId);
        if (exec == null || exec.selectedModel == null || exec.selectedModel.isBlank()) {
            return null;
        }
        return new WorkflowModels.Choice(exec.selectedProvider, exec.selectedModel);
    }

    private CheckpointRunControl controlFor(Exec exec) {
        requireJournal();
        return controls.computeIfAbsent(exec.workspace, workspace -> CheckpointRunControl.open(
                Path.of(workspace),
                List.of(NodeTask.of("workflow", this::runNode)),
                traces,
                new MemoryTrace(),
                environment));
    }

    private CompletionStage<String> hang(Exec exec) {
        CompletableFuture<String> hanging = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                controlFor(exec).interrupt(exec.executionId, exec.userId).toCompletableFuture().join();
            } catch (RuntimeException ex) {
                hanging.completeExceptionally(ex);
            }
        });
        return hanging;
    }

    private void continueRun(Exec exec) {
        try {
            CheckpointRunControl control = controlFor(exec);
            synchronized (exec) {
                exec.suspended = false;
                exec.status = "RUNNING";
                exec.errorCode = "";
                save(exec, true);
            }
            ExecutionSnapshot snapshot = control.resume(exec.executionId, exec.userId).toCompletableFuture().join();
            applySnapshot(exec, snapshot);
        } catch (RuntimeException ex) {
            synchronized (exec) {
                exec.suspended = true;
                exec.status = "SUSPENDED";
                exec.errorCode = offlineCode(ex);
                exec.append("resume failed " + exec.errorCode);
                save(exec, true);
            }
        }
    }

    private void applySnapshot(Exec exec, ExecutionSnapshot snapshot) {
        synchronized (exec) {
            String code = snapshot.errorCode() == null ? "" : snapshot.errorCode();
            if (!modelReady && snapshot.status() == ExecutionStatus.FAILED && ("LLM".equals(code) || "OFFLINE".equals(exec.errorCode))) {
                exec.status = "FAILED";
                exec.errorCode = "OFFLINE";
            } else {
                exec.status = snapshot.status().name();
                if (!code.isBlank()) {
                    exec.errorCode = code;
                }
            }
            exec.suspended = "SUSPENDED".equals(exec.status);
            if ("SUSPENDED".equals(exec.status) && !exec.logs.toString().contains("terminal SUSPENDED")) {
                exec.append("terminal SUSPENDED");
            }
            if (snapshot.traceId() != null && !snapshot.traceId().isBlank()) {
                exec.traceId = snapshot.traceId();
            }
            if (snapshot.rootSpanId() != null && !snapshot.rootSpanId().isBlank()) {
                exec.rootSpanId = snapshot.rootSpanId();
            }
            if ((exec.output == null || exec.output.isBlank()) && snapshot.output() != null && !snapshot.output().isBlank()) {
                exec.output = snapshot.output();
            }
            save(exec, true);
        }
    }

    private void save(Exec exec, boolean status) {
        requireJournal();
        journal.saveDocument(exec.executionId, exec.sessionId, ExecutionJournal.writeJson(exec.state()));
        if (status) {
            journal.updateStatus(exec.executionId, exec.status);
        }
    }

    private void overlay(Exec exec) {
        if (journal == null) {
            return;
        }
        ExecutionRow row = journal.findIfPresent(exec.executionId);
        if (row == null) {
            return;
        }
        synchronized (exec) {
            exec.status = row.status();
            exec.suspended = "SUSPENDED".equals(row.status());
            if (row.otelTraceId() != null && !row.otelTraceId().isBlank()) {
                exec.traceId = row.otelTraceId();
            }
            if (row.otelRootSpanId() != null && !row.otelRootSpanId().isBlank()) {
                exec.rootSpanId = row.otelRootSpanId();
            }
        }
    }

    private Exec load(String executionId) {
        if (journal == null) {
            return null;
        }
        ExecutionRow row = journal.findIfPresent(executionId);
        if (row == null) {
            return null;
        }
        String document = journal.document(executionId);
        if (document == null || document.isBlank()) {
            Exec exec = new Exec(row.executionId(), row.userId(), row.workspacePath(), "");
            exec.sessionId = row.sessionId();
            exec.status = row.status();
            exec.traceId = row.otelTraceId() == null ? "" : row.otelTraceId();
            exec.rootSpanId = row.otelRootSpanId() == null ? "" : row.otelRootSpanId();
            return exec;
        }
        return Exec.restore(ExecutionJournal.readJson(document), row);
    }

    private Exec requireExec(String executionId) {
        Exec exec = executions.get(executionId);
        if (exec != null) {
            return exec;
        }
        exec = load(executionId);
        if (exec == null) {
            throw new AgentException(ErrorCode.PERSIST, "执行不存在", executionId, null);
        }
        executions.put(executionId, exec);
        return exec;
    }

    private AgentException failure(Exec exec) {
        if ("OFFLINE".equals(exec.errorCode) || (!modelReady && exec.modelNode)) {
            return new AgentException(ErrorCode.LLM, "模型未配置", exec.executionId, null);
        }
        ErrorCode code = ErrorCode.INTERNAL;
        try {
            if (exec.errorCode != null && !exec.errorCode.isBlank()) {
                code = ErrorCode.valueOf(exec.errorCode);
            }
        } catch (IllegalArgumentException ignored) {
            code = ErrorCode.INTERNAL;
        }
        return new AgentException(code, exec.errorCode == null || exec.errorCode.isBlank() ? "执行失败" : exec.errorCode, exec.executionId, null);
    }

    private String offlineCode(RuntimeException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof AgentException agent && agent.code() == ErrorCode.LLM && !modelReady) {
                return "OFFLINE";
            }
            if (current instanceof AgentException agent) {
                return agent.code().name();
            }
            current = current.getCause();
        }
        return ErrorCode.INTERNAL.name();
    }

    private static boolean terminalStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) || "SUSPENDED".equals(status) || "OFFLINE".equals(status);
    }

    private static boolean hasSystem(Exec exec) {
        synchronized (exec.timeline) {
            for (Map<String, Object> message : exec.timeline) {
                if ("system".equals(message.get("type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private PluginToolCatalog pluginTools() {
        if (pluginTools == null) {
            pluginTools = PluginToolCatalog.load(passGate);
        }
        return pluginTools;
    }

    private List<String> pluginNames() {
        try {
            return pluginTools().names();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static Map<String, String> argumentMap(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        String text = raw.trim();
        if (text.startsWith("{")) {
            try {
                Map<String, Object> parsed = JSON.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
                });
                for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                    values.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                }
                return values;
            } catch (Exception ignored) {
                // 原文不是对象时按查询和链接记下。
            }
        }
        values.put("query", text);
        values.put("url", text);
        values.put("repo", text);
        values.put("arguments", text);
        return values;
    }

    private static List<Map<String, Object>> copyMaps(List<Map<String, Object>> source) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : source) {
            rows.add(new LinkedHashMap<>(item));
        }
        return rows;
    }

    private static String hex(int length) {
        String raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return raw.substring(0, length);
    }

    private static final class Exec {
        private final String executionId;
        private final String userId;
        private final String workspace;
        private final String token;
        private final StringBuilder logs = new StringBuilder();
        private final List<String> queue = new ArrayList<>();
        private final List<Map<String, Object>> retrievals = new ArrayList<>();
        private final List<Map<String, Object>> passes = new ArrayList<>();
        private final List<String> tools = new ArrayList<>();
        private final List<String> plugins = new ArrayList<>();
        private final List<String> evaluations = new ArrayList<>();
        private final List<Map<String, Object>> agents = new ArrayList<>();
        private final List<Map<String, Object>> timeline = new ArrayList<>();
        private final AtomicInteger toolSequence = new AtomicInteger();
        private String traceId = "";
        private String rootSpanId = "";
        private String sessionId = "";
        private String selectedProvider = "";
        private String selectedModel = "";
        private volatile String status = "ACCEPTED";
        private volatile String output = "";
        private volatile String thinking = "";
        private volatile String errorCode = "";
        private volatile boolean suspended;
        private volatile String pendingWorkflow;
        private volatile String pendingInput;
        private boolean modelNode;
        private boolean resuming;
        private int runs;

        private Exec(String executionId, String userId, String workspace, String token) {
            this.executionId = executionId;
            this.userId = userId;
            this.workspace = workspace;
            this.token = token;
        }

        private void append(String line) {
            logs.append(line).append('\n');
        }

        private Map<String, Object> state() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("executionId", executionId);
            body.put("userId", userId);
            body.put("workspace", workspace);
            body.put("token", token);
            body.put("sessionId", sessionId);
            body.put("selectedProvider", selectedProvider);
            body.put("selectedModel", selectedModel);
            body.put("traceId", traceId);
            body.put("rootSpanId", rootSpanId);
            body.put("status", status);
            body.put("output", output);
            body.put("thinking", thinking == null ? "" : thinking);
            body.put("errorCode", errorCode);
            body.put("suspended", suspended);
            body.put("pendingWorkflow", pendingWorkflow);
            body.put("pendingInput", pendingInput);
            body.put("modelNode", modelNode);
            body.put("runs", runs);
            body.put("toolSequence", toolSequence.get());
            body.put("logs", logs.toString());
            body.put("queue", List.copyOf(queue));
            body.put("retrievals", copyMaps(retrievals));
            body.put("passes", copyMaps(passes));
            body.put("tools", List.copyOf(tools));
            body.put("plugins", List.copyOf(plugins));
            body.put("evaluations", List.copyOf(evaluations));
            body.put("agents", copyMaps(agents));
            synchronized (timeline) {
                body.put("timeline", copyMaps(timeline));
            }
            return body;
        }

        private static Exec restore(Map<String, Object> state, ExecutionRow row) {
            Exec exec = new Exec(
                    row.executionId(),
                    row.userId(),
                    row.workspacePath(),
                    text(state.get("token")));
            exec.sessionId = row.sessionId();
            exec.selectedProvider = text(state.get("selectedProvider"));
            exec.selectedModel = text(state.get("selectedModel"));
            exec.traceId = row.otelTraceId() == null ? text(state.get("traceId")) : row.otelTraceId();
            exec.rootSpanId = row.otelRootSpanId() == null ? text(state.get("rootSpanId")) : row.otelRootSpanId();
            exec.status = row.status();
            exec.output = text(state.get("output"));
            exec.thinking = text(state.get("thinking"));
            exec.errorCode = text(state.get("errorCode"));
            exec.suspended = "SUSPENDED".equals(row.status());
            exec.pendingWorkflow = text(state.get("pendingWorkflow"));
            exec.pendingInput = text(state.get("pendingInput"));
            exec.modelNode = Boolean.TRUE.equals(state.get("modelNode"));
            exec.runs = number(state.get("runs"));
            exec.toolSequence.set(number(state.get("toolSequence")));
            exec.logs.append(text(state.get("logs")));
            exec.queue.addAll(strings(state.get("queue")));
            exec.retrievals.addAll(maps(state.get("retrievals")));
            exec.passes.addAll(maps(state.get("passes")));
            exec.tools.addAll(strings(state.get("tools")));
            exec.plugins.addAll(strings(state.get("plugins")));
            exec.evaluations.addAll(strings(state.get("evaluations")));
            exec.agents.addAll(maps(state.get("agents")));
            exec.timeline.addAll(maps(state.get("timeline")));
            return exec;
        }

        private static String text(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private static int number(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value == null || String.valueOf(value).isBlank()) {
                return 0;
            }
            return Integer.parseInt(String.valueOf(value));
        }

        private static List<String> strings(Object value) {
            List<String> rows = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    rows.add(item == null ? "" : String.valueOf(item));
                }
            }
            return rows;
        }

        private static List<Map<String, Object>> maps(Object value) {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        map.forEach((key, entry) -> row.put(String.valueOf(key), entry));
                        rows.add(row);
                    }
                }
            }
            return rows;
        }
    }

    private static final class OfflineChatModel implements dev.jasagent.api.llm.ChatModel {
        @Override
        public CompletionStage<ChatResponse> complete(dev.jasagent.api.llm.ChatRequest request) {
            String executionId = request == null ? null : request.executionId();
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", executionId, null));
        }

        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", executionId, null));
        }
    }

    private static final class MemoryTrace implements TraceWriter {
        @Override
        public CompletionStage<Void> event(Path workspace, TraceEvent event) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<String> readLog(Path workspace, String executionId) {
            return CompletableFuture.completedFuture("");
        }
    }
}
