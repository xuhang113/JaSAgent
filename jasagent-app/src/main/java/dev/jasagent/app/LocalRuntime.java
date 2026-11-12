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
import dev.jasagent.api.pass.ExternalEgress;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.TicketRequest;
import dev.jasagent.api.persist.DataDirectory;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.runtime.AssignedExecution;
import dev.jasagent.api.runtime.CallerScope;
import dev.jasagent.api.runtime.GatewayHeaders;
import dev.jasagent.api.tool.ReadOnlyTools;
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
import dev.jasagent.persist.SessionLedger;
import dev.jasagent.persist.UragMysql;
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

/**
 * 本机执行与会话运行时。它把登录会话、工作流、检查点、通行证、会话文件和模型选择连在一起，对外提供执行接口和控制台接口。
 */
public final class LocalRuntime implements ExecutionApi, ConsoleApi {
    /**
     * 读写会话文件、工具参数和执行文档的 JSON 映射器。
     */
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LocalAuthService auth;
    private final PassGate passGate;
    private final QueueAgentScheduler scheduler;
    private final double compressAt;
    private final boolean modelReady;
    private final ChatModel workflowChat;
    private final SessionModelCatalog catalog;
    private final ExecutionJournal journal;
    private final SessionLedger ledger;
    private final boolean egressEnabled;
    private final HttpOneTimeTicketClient ticketClient;
    private final RemoteRagClient remoteRag = new RemoteRagClient();
    private final String ragTarget;
    private final AtomicInteger observedModelCalls = new AtomicInteger();
    private final AtomicInteger observedToolCalls = new AtomicInteger();
    /**
     * 按租户、用户、会话记下的工具参数。只作同进程缓存，归属以 MySQL 里的会话行为准。
     */
    private final Map<String, List<String>> toolNotes = new ConcurrentHashMap<>();
    private final SavedTraceTracer traces = new SavedTraceTracer();
    private final Map<String, String> environment;
    private final Map<String, CheckpointRunControl> controls = new ConcurrentHashMap<>();
    private final Map<String, Exec> executions = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private PluginToolCatalog pluginTools;

    /**
     * 用空配置启动运行时，模型目录从进程环境读取。
     * 副作用：会继续完成通行证、调度器、执行日志、检查点和思考回调的初始化。
     *
     * @param auth 本机用户、口令和工作区目录
     */
    public LocalRuntime(LocalAuthService auth) {
        this(auth, Map.of());
    }

    /**
     * 按给定配置启动运行时，模型目录仍从进程环境读取。
     * 副作用：配置会传给通行证、压缩阈值和思考开关，并尝试打开存储。
     *
     * @param auth 本机认证服务
     * @param configuration 通行证、压缩比例、思考开关和 JDBC 等覆盖项
     */
    public LocalRuntime(LocalAuthService auth, Map<String, String> configuration) {
        this(auth, configuration, SessionModelCatalog.fromEnvironment());
    }

    /**
     * 用指定模型目录启动运行时，测试可以注入固定目录而不访问外网。
     * 副作用：创建通行证和调度器，包装对话模型以记录思考，绑定思考增量，尝试打开执行日志和检查点；打不开时日志留空，健康检查会报存储不可用。
     *
     * @param auth 本机认证服务
     * @param configuration 运行覆盖项，可以是空映射
     * @param catalog 当前可选的 DeepSeek 与 Gateway 模型
     */
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
            observedModelCalls.incrementAndGet();
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
        SessionLedger sessions = null;
        if (opened != null && opened.database().settings().mysql()) {
            try {
                sessions = SessionLedger.open(opened.database());
            } catch (RuntimeException ignored) {
                sessions = null;
            }
        }
        this.ledger = sessions;
        this.egressEnabled = configuration != null && "true".equalsIgnoreCase(configuration.get("jasagent.egress.enabled"));
        String ticketUrl = configuration == null ? "" : configuration.get("jasagent.egress.ticket-url");
        if (ticketUrl == null || ticketUrl.isBlank()) {
            ticketUrl = ExternalEgress.TICKET_ENDPOINT;
        }
        this.ticketClient = this.egressEnabled ? new HttpOneTimeTicketClient(ticketUrl) : null;
        String rag = configuration == null ? "" : configuration.get("jasagent.rag.base-url");
        this.ragTarget = rag == null || rag.isBlank() ? ExternalEgress.RAG_TARGET : rag;
        try {
            CheckpointStores.prepare(this.environment);
        } catch (RuntimeException ignored) {
            // 健康检查会把连不上的检查点报成 storage down。
        }
    }

    /**
     * 执行日志和检查点是否都能查询。日志没打开时直接不可用。
     *
     * @return 两处都可查询时为 true
     */
    public boolean storageReachable() {
        return journal != null && journal.reachable() && CheckpointStores.reachable(environment);
    }

    /**
     * 这次运行时里，子 span 记录过的 trace 副本，用来核对恢复后没有换 trace。
     *
     * @return 不可变列表
     */
    List<String> observedChildTraceIds() {
        return traces.childTraceIds();
    }

    /**
     * 丢掉检查点控制器并立刻打断工作线程，进行中的节点不再继续。
     * 副作用：清空 controls，并对虚拟线程池执行 shutdownNow。
     */
    public void close() {
        controls.clear();
        workers.shutdownNow();
    }

    /**
     * 提交一次新执行。会话编号与执行编号相同，不额外指定模型。
     *
     * @param userId 发起人登录名
     * @param token 本机会话令牌
     * @param workflowId 工作流编号，answer-with-tool 使用内置示例
     * @param input 交给工作流的用户输入
     * @return 含 executionId 和 ACCEPTED 的映射
     */
    @Override
    public Map<String, Object> submit(String userId, String token, String workflowId, String input) {
        return submit(userId, token, workflowId, input, null);
    }

    /**
     * 提交执行并挂到已有会话，模型选择留空，改由会话文档决定。
     *
     * @param userId 发起人登录名
     * @param token 本机会话令牌
     * @param workflowId 工作流编号
     * @param input 用户输入
     * @param sessionId 会话编号，空白时改用新的执行编号
     * @return 含 executionId 和 ACCEPTED 的映射
     */
    private Map<String, Object> submit(String userId, String token, String workflowId, String input, String sessionId) {
        return submit(userId, token, workflowId, input, sessionId, "", "");
    }

    /**
     * 真正创建执行：校验工作区，生成 trace，记录所选模型，把子代理放进队列，插入数据库并在虚拟线程里启动。
     * 副作用：写入内存执行表、文本日志和数据库，并异步调用 start。
     *
     * @param userId 发起人登录名
     * @param token 本机会话令牌，工作区为空时拒绝
     * @param workflowId 工作流编号
     * @param input 用户输入，null 当空串
     * @param sessionId 会话编号，空白时用执行编号
     * @param provider 模型供应商，可以是空串
     * @param model 模型 id，非空时写进日志
     * @return 含 executionId 和 ACCEPTED 的映射
     */
    private Map<String, Object> submit(
            String userId,
            String token,
            String workflowId,
            String input,
            String sessionId,
            String provider,
            String model) {
        requireJournal();
        String workspace;
        String keptToken;
        CallerScope scope = CallerScope.current();
        if (scope != null || token == null || token.isBlank()) {
            if (scope != null && !userId.equals(scope.userId())) {
                throw new AgentException(ErrorCode.AUTH, GatewayHeaders.OWNER_MISMATCH);
            }
            String tenant = scope == null ? GatewayHeaders.TENANT_LOCAL : scope.tenantId();
            String chosen = sessionId == null || sessionId.isBlank() ? "" : sessionId;
            if (scope != null && !scope.sessionId().isBlank() && !chosen.isBlank() && !scope.sessionId().equals(chosen)) {
                throw new AgentException(ErrorCode.AUTH, "网关会话与请求会话不一致");
            }
            if (ledger != null && !chosen.isBlank()) {
                ledger.assertOwner(tenant, userId, chosen);
            }
            workspace = workspaceOf(tenant, userId);
            if (workspace.isBlank()) {
                throw new AgentException(ErrorCode.WORKFLOW, "未选择工作区");
            }
            keptToken = "";
        } else {
            Session session = auth.authenticate(token).toCompletableFuture().join();
            if (session.workspace() == null || session.workspace().isBlank()) {
                throw new AgentException(ErrorCode.WORKFLOW, "未选择工作区");
            }
            workspace = Path.of(session.workspace()).toAbsolutePath().normalize().toString();
            keptToken = session.token();
        }
        String id = assignedExecutionId();
        Exec exec = new Exec(id, userId, workspace, keptToken);
        exec.tenantId = scope == null ? GatewayHeaders.TENANT_LOCAL : scope.tenantId();
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
        if (SubAgentNeed.required(text)) {
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
                agent.put("title", text);
                exec.agents.add(agent);
                exec.queue.add(snap.agentId() + ": " + workflowId);
            }
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

    /**
     * 列出该用户的执行编号和状态。
     *
     * @param userId 用户登录名
     * @return 每项只含 executionId 和 status
     */
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

    /**
     * 读取该用户自己的执行详情，并用数据库里的状态覆盖内存。
     *
     * @param userId 必须与执行所有者相同
     * @param executionId 执行编号
     * @return 输出、思考、错误码、队列、日志、检索、通行证、评估、工具、插件和 trace
     */
    @Override
    public Map<String, Object> detail(String userId, String executionId) {
        return view(owned(userId, executionId));
    }

    /**
     * 返回该用户这次执行累积的文本日志。
     *
     * @param userId 所有者登录名
     * @param executionId 执行编号
     * @return 以换行分隔的日志，尚无内容时是空串
     */
    @Override
    public String logs(String userId, String executionId) {
        return owned(userId, executionId).logs.toString();
    }

    /**
     * 请求中止执行。控制面调用失败时，只要还没到终态，就把执行改成挂起并落库。
     * 副作用：追加 interrupt 日志，可能把状态写成 SUSPENDED。
     *
     * @param userId 所有者登录名
     * @param executionId 执行编号
     * @return 中止之后的执行视图
     */
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

    /**
     * 从检查点继续挂起的执行。不是 SUSPENDED 时不改状态。恢复失败则回到挂起并记下错误码。
     * 副作用：成功路径会先写成 RUNNING 再应用快照；失败路径追加 resume failed。
     *
     * @param userId 所有者登录名
     * @param executionId 执行编号
     * @return 恢复后的视图；本来不是挂起时是原视图
     */
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

    /**
     * 先确认令牌有效，再按查询原文做一条工作区检索。scopes 目前不参与过滤。
     *
     * @param userId 调用方登录名，当前不参与过滤
     * @param token 必须能通过认证的令牌
     * @param query 检索原文，会放进命中的 text
     * @param scopes 调用方传来的范围，当前不读取
     * @return 含空 reason 和一条 workspace 命中的映射
     */
    @Override
    public Map<String, Object> search(String userId, String token, String query, List<String> scopes) {
        if (token == null || token.isBlank()) {
            CallerScope scope = CallerScope.current();
            if (scope == null || !userId.equals(scope.userId())) {
                throw new AgentException(ErrorCode.AUTH, "缺少网关身份");
            }
        } else {
            Session session = auth.authenticate(token).toCompletableFuture().join();
            if (!userId.equals(session.login())) {
                throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的执行");
            }
        }
        return retrieval(query);
    }

    /**
     * 把工作区记到 MySQL。直接调用且还没有账本时，退回登录会话上的工作区选择，不改口令实现。
     *
     * @param userId 网关用户 id
     * @param path 请求里的路径
     * @param allowlist 允许的工作区文本
     * @return 含 workspace 的映射
     */
    @Override
    public Map<String, String> bindWorkspace(String userId, String path, List<String> allowlist) {
        List<Path> paths = new ArrayList<>();
        if (allowlist != null) {
            for (String item : allowlist) {
                if (item != null && !item.isBlank()) {
                    paths.add(Path.of(item));
                }
            }
        }
        Path selected = auth.directories().checkWorkspace(path == null ? null : Path.of(path), paths, Path.of("").toAbsolutePath())
                .toCompletableFuture()
                .join();
        String tenant = CallerScope.tenantOrLocal();
        if (ledger != null) {
            ledger.saveWorkspace(tenant, userId, selected.toString());
        }
        return Map.of("workspace", selected.toString());
    }

    /**
     * 读取这个用户在当前租户下的工作区。
     *
     * @param userId 用户 id
     * @return 工作区路径；没有时是空串
     */
    @Override
    public String workspace(String userId) {
        if (ledger == null) {
            return "";
        }
        return ledger.workspace(CallerScope.tenantOrLocal(), userId);
    }

    /**
     * 本进程里模型实际被请求的次数。冲突拒绝的调用不应增加它。
     *
     * @return 次数
     */
    int observedModelCalls() {
        return observedModelCalls.get();
    }

    /**
     * 本进程里工具实际开始执行的次数。被拒绝的调用不增加它。
     *
     * @return 次数
     */
    int observedToolCalls() {
        return observedToolCalls.get();
    }

    /**
     * 按租户、用户和会话读出已经记下的工具参数，用来核对同进程里的两次调用没有串数据。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     * @param sessionId 会话 id
     * @return 工具名和参数，没有时是空列表
     */
    List<String> scopedToolNotes(String tenantId, String userId, String sessionId) {
        List<String> notes = toolNotes.get(scopeKey(tenantId, userId, sessionId));
        return notes == null ? List.of() : List.copyOf(notes);
    }

    /**
     * 让检查点控制器启动这次执行，并把快照写回内存。启动失败时记成失败并落库。
     * 副作用：改变执行状态，并可能写数据库。
     *
     * @param exec 刚接受、尚未进入节点的执行
     */
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

    /**
     * 把工作流节点丢到虚拟线程，返回节点结束时的输出阶段。
     *
     * @param executionId 要运行的执行
     * @return 成功时完成输出文本；挂起或失败时异常完成
     */
    private CompletionStage<String> runNode(String executionId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        workers.execute(() -> runWorkflow(executionId, future));
        return future;
    }

    /**
     * 跑完工作流后完成节点阶段。挂起或仍有待批通行证时，让阶段以取消异常结束并触发一次中断；失败则异常完成；成功则完成输出。
     * 副作用：可能把失败写入执行和数据库，并完成或异常完成 future。
     *
     * @param executionId 执行编号
     * @param future 交给检查点节点的完成口
     */
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

    /**
     * 装载工作流，补上 system 和 human 时间线，调用引擎，再把结果收成成功、离线失败或挂起。
     * 副作用：改状态、输出、思考、评估、错误码和时间线。目录读不到时仍保留开场消息。
     *
     * @param exec 已经写好待运行工作流和输入的执行
     */
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

    /**
     * 把助手角色、规则、技能和用户输入写成时间线上的 system 与 human 两条。
     * 副作用：向时间线追加这两条消息。
     *
     * @param exec 接收时间线的执行
     * @param rules 规则的 id 和 description
     * @param skills 技能的 name 和 description
     * @param input 用户原文，null 当空串
     */
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

    /**
     * 把工作流返回的思考并进执行。空白思考忽略，已包含的片段不重复追加。
     * 副作用：可能改写 exec 的思考全文。
     *
     * @param exec 当前执行
     * @param result 引擎返回的这一趟结果，可以是 null
     */
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

    /**
     * 把模型流式吐出的思考增量接到对应执行上。执行编号或增量是空时忽略。
     * 副作用：拼接该执行的思考全文。
     *
     * @param executionId 正在生成的执行
     * @param delta 这一小段思考，可以是空
     */
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

    /**
     * 用模型最终返回的思考替换或补上这次调用期间的增量，并在时间线记一条 thinking。没有思考文本时不做任何事。
     * 副作用：可能改写思考全文，并追加时间线。
     *
     * @param exec 这次调用所属的执行，可以是 null
     * @param reply 模型回复，可以是 null
     * @param mark 调用开始时思考文本的长度，用来切开已有内容和本次流式片段
     * @param elapsedMs 这次模型调用的毫秒耗时
     */
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

    /**
     * 用一个空行把已有思考和本次思考接起来。
     *
     * @param prior 调用开始前已经有的思考，可以是 null
     * @param thought 本次要接上的思考
     * @return 拼接后的全文；没有前文时就是 thought
     */
    private static String joinThought(String prior, String thought) {
        String head = prior == null ? "" : prior.stripTrailing();
        if (head.isEmpty()) {
            return thought;
        }
        return head + "\n\n" + thought;
    }

    /**
     * 把起始纳秒换算成已经过去的毫秒，结果不会为负。
     *
     * @param started System.nanoTime 记下的起点
     * @return 毫秒
     */
    private static long elapsed(long started) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /**
     * 在时间线追加一条 ai。当前有思考时一并放进这条消息。
     * 副作用：时间线增加一条，工具调用列表为空。
     *
     * @param exec 接收时间线的执行
     * @param content 回复正文，null 当空串
     */
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

    /**
     * 在时间线追加一条 thinking，记下内容和耗时。
     * 副作用：时间线增加一条。
     *
     * @param exec 接收时间线的执行
     * @param thought 思考正文，null 当空串
     * @param elapsedMs 产生这段思考所用的毫秒
     */
    private void recordThinking(Exec exec, String thought, long elapsedMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "thinking");
        row.put("content", thought == null ? "" : thought);
        row.put("elapsedMs", elapsedMs);
        synchronized (exec.timeline) {
            exec.timeline.add(row);
        }
    }

    /**
     * 在时间线追加一条 tool。调用编号按该执行内的序号递增。
     * 副作用：时间线增加一条，工具序号加一。
     *
     * @param exec 接收时间线的执行
     * @param name 工具名
     * @param result 工具结果，null 当空串
     * @param elapsedMs 这次调用的毫秒耗时
     * @param arguments 调用参数原文，null 当空串
     */
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

    /**
     * 为这一趟运行在工作区数据目录准备一个新的检查点库路径，打不开目录时抛出持久化异常。
     * 副作用：该执行的运行次数加一，并创建数据目录。
     *
     * @param exec 需要检查点的执行
     * @return 形如执行编号加序号的 db 路径
     */
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

    /**
     * 把异常收成错误码。执行已经挂起、且不是存储或离线错误时保持挂起，否则改成 FAILED。
     * 副作用：改状态和错误码，并可能追加 ai 消息和终态日志。
     *
     * @param exec 失败的执行
     * @param ex 节点或引擎抛出的运行时异常
     */
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

    /**
     * 把执行标成挂起。输出非空时保留这段输出。
     * 副作用：suspended 设为真，状态写成 SUSPENDED。
     *
     * @param exec 要挂起的执行
     * @param output 目前已经产生的输出，空白时不覆盖原输出
     */
    private void finishSuspended(Exec exec, String output) {
        exec.suspended = true;
        exec.status = "SUSPENDED";
        if (output != null && !output.isBlank()) {
            exec.output = output;
        }
    }

    /**
     * 是否还有被挡住、决定仍是 PENDING 的通行证。
     *
     * @param exec 含通行证列表的执行
     * @return 存在阻塞中的待决定通行证时为 true
     */
    private static boolean pending(Exec exec) {
        for (Map<String, Object> pass : exec.passes) {
            if (PassDecision.PENDING.name().equals(String.valueOf(pass.get("decision")))
                    && Boolean.TRUE.equals(pass.get("blocked"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为插件取得或复用通行证。决定仍是 PENDING 时把执行标成挂起。插件编号为空时什么都不做。
     * 副作用：可能新增通行证、追加日志，并改成 SUSPENDED。
     *
     * @param exec 当前执行
     * @param pluginId 插件编号，可以是空白
     * @return 已完成的空阶段
     */
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

    /**
     * 按通行证决定拒绝、等待批准或真正执行工具，并把结果写入时间线。clock 不走通行证。非模型节点在恢复过程中即使还在等待也不再把执行标成挂起。
     * 副作用：可能挂起执行、记录检索或调用插件。
     *
     * @param exec 当前执行
     * @param name 工具名
     * @param arguments 工具参数原文
     * @return 工具结果，或 denied、approval-required
     */
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
        } else if ("rag.search".equals(name) && egressEnabled) {
            ExternalEgress.Outcome outcome = ExternalEgress.permit(
                    PassDecision.ALLOW,
                    ExternalEgress.Kind.EXTERNAL,
                    ticketClient,
                    new TicketRequest(exec.tenantId, exec.userId, exec.sessionId, exec.executionId, ragTarget, name));
            if (!outcome.invoke()) {
                recordTool(exec, name, outcome.detail(), elapsed(started), arguments);
                return CompletableFuture.completedFuture(outcome.detail());
            }
            result = remoteRag.search(ragTarget, arguments, outcome.detail(), exec.executionId, exec.sessionId, exec.executionId);
            exec.retrievals.add(retrieval(result));
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
        observedToolCalls.incrementAndGet();
        noteTool(exec, name, arguments);
        recordTool(exec, name, result, elapsed(started), arguments);
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 调用已装载的插件。成功记入统计；失败返回错误文本，统计写不进去时仍把失败留在调用方的时间线。
     *
     * @param exec 提供用户、执行和工作区的执行
     * @param name 插件工具名
     * @param arguments 参数原文，会拆成字符串映射
     * @return 插件输出；失败时是错误信息，不会是 null
     */
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

    /**
     * 把 rag.search 的参数包成一条工作区命中。
     *
     * @param arguments 检索原文，null 当空串
     * @return 含空 reason 和一条 sourceScope 为 workspace 的命中
     */
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

    /**
     * 从内存或日志取出执行，确认属于该用户，并用数据库状态覆盖内存。别人的执行或找不到时抛出认证异常。
     * 副作用：首次从磁盘装载时放进内存表。
     *
     * @param userId 请求者登录名
     * @param executionId 执行编号
     * @return 该用户的执行
     */
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
            throw new AgentException(ErrorCode.AUTH, exec == null ? "不能读取其他用户的执行" : GatewayHeaders.OWNER_MISMATCH);
        }
        CallerScope scope = CallerScope.current();
        if (scope != null && exec.tenantId != null && !exec.tenantId.isBlank() && !scope.tenantId().equals(exec.tenantId)) {
            throw new AgentException(ErrorCode.AUTH, GatewayHeaders.OWNER_MISMATCH);
        }
        overlay(exec);
        return exec;
    }

    /**
     * 抽出执行编号和当前状态，作为详情的前两项。
     *
     * @param exec 执行
     * @return 含 executionId 和 status 的映射
     */
    private static Map<String, Object> summary(Exec exec) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("executionId", exec.executionId);
        row.put("status", exec.status);
        return row;
    }

    /**
     * 在执行锁内组装详情。列表都是副本，调用方改不回内部状态。
     *
     * @param exec 要展示的执行
     * @return 输出、思考、错误码、队列、日志、检索、通行证、评估、工具、插件和 trace
     */
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

    /**
     * 列出内置的 answer-with-tool，以及当前工作区 workflows 目录里的 json 工作流。令牌必须属于该用户。
     *
     * @param userId 用户登录名
     * @param token 该用户的会话令牌
     * @return 每项只有 id
     */
    @Override
    public List<Map<String, Object>> workflows(String userId, String token) {
        String workspace;
        if (token == null || token.isBlank()) {
            CallerScope scope = CallerScope.current();
            if (scope != null && !userId.equals(scope.userId())) {
                throw new AgentException(ErrorCode.AUTH, GatewayHeaders.OWNER_MISMATCH);
            }
            workspace = workspaceOf(CallerScope.tenantOrLocal(), userId);
        } else {
            Session session = auth.authenticate(token).toCompletableFuture().join();
            if (!userId.equals(session.login())) {
                throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的执行");
            }
            workspace = session.workspace();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add("answer-with-tool");
        if (workspace != null && !workspace.isBlank()) {
            Path root = Path.of(workspace).resolve("jasagent").resolve("workflows");
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

    /**
     * 列出该用户目录里的技能名称和说明。
     *
     * @param userId 用户登录名
     * @param token 该用户的会话令牌
     * @return 每项含 name 和 description
     */
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

    /**
     * 按创建时间列出该用户的会话摘要。目录不存在时是空列表，文件里的用户对不上就跳过。
     *
     * @param userId 用户登录名
     * @return 每项含 sessionId、title、model、modelProvider 和 createdAt
     */
    @Override
    public List<Map<String, Object>> sessions(String userId) {
        if (ledger != null) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SessionLedger.Row row : ledger.list(CallerScope.tenantOrLocal(), userId)) {
                Map<String, Object> stored = new LinkedHashMap<>();
                stored.put("sessionId", row.sessionId());
                stored.put("userId", row.userId());
                stored.put("title", row.title());
                stored.put("titleCustom", row.titleCustom());
                stored.put("model", row.model());
                stored.put("modelProvider", row.provider());
                stored.put("createdAt", row.createdAt());
                rows.add(sessionSummary(stored));
            }
            return rows;
        }
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

    /**
     * 新建会话文件。自动标题、空白标题和“会话 数字”都记成“新会话”，手写标题截到 80 字。模型默认 deepseek-flash。
     * 副作用：在该用户的 conversations 目录写下 json。
     *
     * @param userId 所有者登录名
     * @param title 请求里的标题，可以是空白
     * @return 新建会话的摘要
     */
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
        stored.put("tenantId", CallerScope.tenantOrLocal());
        stored.put("messages", new ArrayList<>());
        stored.put("corrections", new ArrayList<>());
        storeSession(stored);
        return sessionSummary(stored);
    }

    /**
     * 把会话标题改成用户指定的文字，并记为手改过。去掉空白后为空则拒绝。
     * 副作用：覆写会话文件。
     *
     * @param userId 所有者登录名
     * @param sessionId 会话编号
     * @param title 新标题
     * @return 更新后的摘要
     */
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
        storeSession(stored);
        return sessionSummary(stored);
    }

    /**
     * 校验模型仍在当前目录中，然后写入会话。
     * 副作用：覆写会话文件里的 model 和 modelProvider。
     *
     * @param userId 所有者登录名
     * @param sessionId 会话编号
     * @param model 模型 id
     * @param provider 供应商，空白时按 id 唯一匹配
     * @return 更新后的摘要
     */
    @Override
    public Map<String, Object> updateModel(String userId, String sessionId, String model, String provider) {
        Path file = conversationFile(userId, sessionId);
        Map<String, Object> stored = readOwned(userId, file);
        SessionModelCatalog.ModelChoice choice = catalog.require(model, provider);
        stored.put("model", choice.id());
        stored.put("modelProvider", choice.provider());
        storeSession(stored);
        return sessionSummary(stored);
    }

    /**
     * 列出当前进程可选的模型。用户编号不参与过滤。
     *
     * @param userId 请求者登录名，只为对齐接口
     * @return 含 defaultModel 和 models 数组的映射
     */
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

    /**
     * 删除该用户的会话文件。先核对所有者，删不掉时抛出内部异常。
     * 副作用：磁盘上的会话 json 被删除。
     *
     * @param userId 所有者登录名
     * @param sessionId 会话编号
     * @return 含 sessionId 和 deleted 为 true 的映射
     */
    @Override
    public Map<String, Object> deleteSession(String userId, String sessionId) {
        Path file = conversationFile(userId, sessionId);
        Map<String, Object> stored = readOwned(userId, file);
        if (ledger != null) {
            ledger.delete(CallerScope.tenantOrLocal(), userId, sessionId);
        } else {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ex) {
                throw new AgentException(ErrorCode.INTERNAL, "会话无法删除", null, ex);
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", stored.get("sessionId"));
        row.put("deleted", true);
        return row;
    }

    /**
     * 读出会话全文，补上解析后的模型、供应商和纠正笔记，并保证 agents 字段存在。
     *
     * @param userId 所有者登录名
     * @param sessionId 会话编号
     * @return 会话映射
     */
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

    /**
     * 沿用会话里已经选定的模型追加一条用户消息，不在这次请求里改模型。
     *
     * @param userId 所有者登录名
     * @param token 该用户的会话令牌
     * @param sessionId 会话编号
     * @param text 用户消息
     * @param workflowId 工作流编号，空白时用 answer-with-tool
     * @return 刚写入的用户消息，含 executionId
     */
    @Override
    public Map<String, Object> appendMessage(String userId, String token, String sessionId, String text, String workflowId) {
        return appendMessage(userId, token, sessionId, text, workflowId, null, null);
    }

    /**
     * 追加用户消息。模型非空时先校验并写入会话，再按会话里的供应商和模型提交工作流。第一条消息且标题没手改过时，用消息生成不超过 40 字的标题。
     * 副作用：可能改模型、提交执行、追加消息和子代理，并覆写会话文件。
     *
     * @param userId 所有者登录名
     * @param token 该用户的会话令牌
     * @param sessionId 会话编号
     * @param text 用户消息，空白时拒绝
     * @param workflowId 工作流编号，空白时用 answer-with-tool
     * @param model 要改成的模型 id，空白表示不改
     * @param provider 与 model 一起校验的供应商
     * @return 刚写入的用户消息，含 executionId 和 createdAt
     */
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
            storeSession(stored);
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
        storeSession(stored);
        return message;
    }

    /**
     * 取会话中某次执行的时间线。执行编号为空时，用消息里最后一条带执行编号的记录。该执行必须出现在这条会话的消息里。
     *
     * @param userId 所有者登录名
     * @param sessionId 会话编号
     * @param executionId 执行编号，可以是空白
     * @return 含 sessionId、executionId 和 messages 的映射
     */
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

    /**
     * 组装会话上下文：规则、技能、纠正、消息、预算、压缩阈值、占用和已发布摘要。压缩没完成时不把半截摘要当成上下文。
     * 副作用：会尝试准备一次上下文窗口，失败被吃掉，不改会话文件。
     *
     * @param userId 所有者登录名
     * @param token 用来读取目录的令牌
     * @param sessionId 会话编号
     * @return 上下文映射
     */
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

    /**
     * 为会话追加一条来源是用户的纠正笔记。正文空白时拒绝。
     * 副作用：在该用户的 corrections 目录写下新的 json。
     *
     * @param userId 所有者登录名
     * @param sessionId 会话编号
     * @param text 纠正正文
     * @param executionId 相关执行，null 当空串
     * @return 刚写入的笔记
     */
    @Override
    public Map<String, Object> addCorrection(String userId, String sessionId, String text, String executionId) {
        Map<String, Object> stored = readOwned(userId, conversationFile(userId, sessionId));
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
        if (ledger != null) {
            correctionsIn(stored).add(note);
            storeSession(stored);
            return note;
        }
        writeJson(correctionFile(userId, sessionId, noteId), note);
        return note;
    }

    /**
     * 列出该会话的全部纠正笔记。先确认会话属于该用户。目录不存在时是空列表。
     *
     * @param userId 所有者登录名
     * @param sessionId 会话编号
     * @return 笔记映射列表
     */
    @Override
    public List<Map<String, Object>> corrections(String userId, String sessionId) {
        Map<String, Object> stored = readOwned(userId, conversationFile(userId, sessionId));
        if (ledger != null) {
            return List.copyOf(correctionsIn(stored));
        }
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

    /**
     * 把通行证改成 ALLOW 或 DENY。ALLOW 且执行仍挂着、还有待运行工作流时，异步从检查点继续。其他决定值直接拒绝。找不到通行证时抛出工作流异常。
     * 副作用：改通行证的决定和原因，可能追加 resume 日志并调度继续运行。
     *
     * @param userId 所有者登录名
     * @param executionId 执行编号
     * @param passId 通行证编号
     * @param decision 只能是 ALLOW 或 DENY
     * @return 决定之后的执行视图
     */
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

    /**
     * 从工作流节点收集工具和插件。开启 grounding 时加上 grounding.default。除 clock 外的工具和全部插件都预建通行证。
     * 副作用：填充执行的工具、插件和通行证列表。
     *
     * @param exec 接收能力清单的执行
     * @param document 已装载的工作流
     */
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

    /**
     * 向通行证门申请一次决定并记入执行。种类是 tool 时名字放在工具槽，否则放在插件槽。
     * 副作用：通行证列表增加一行。
     *
     * @param exec 当前执行
     * @param name 工具或插件名
     * @param kind tool 或 plugin
     * @return 新的通行证行，含 passId、决定和原因
     */
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

    /**
     * 按名称和种类查找已经记过的通行证。
     *
     * @param exec 当前执行
     * @param name 工具或插件名
     * @param kind tool 或 plugin
     * @return 找到的行；没有时为 null
     */
    private static Map<String, Object> findPass(Exec exec, String name, String kind) {
        for (Map<String, Object> pass : exec.passes) {
            if (name.equals(pass.get("name")) && kind.equals(pass.get("kind"))) {
                return pass;
            }
        }
        return null;
    }

    /**
     * 装载工作流。编号为空或是 answer-with-tool 时用内置示例，否则读工作区 jasagent/workflows 下的同名 json。文件不存在或解析失败时抛出工作流异常。
     *
     * @param workspace 工作区根路径
     * @param workflowId 工作流编号
     * @return 解析后的定义
     */
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

    /**
     * 确认令牌属于该用户后，装载工作区 jasagent 目录和用户目录里的规则与技能。
     *
     * @param userId 用户登录名
     * @param token 该用户的会话令牌
     * @return 合并后的目录
     */
    private Catalog catalog(String userId, String token) {
        if (token == null || token.isBlank()) {
            CallerScope scope = CallerScope.current();
            if (scope != null && !userId.equals(scope.userId())) {
                throw new AgentException(ErrorCode.AUTH, GatewayHeaders.OWNER_MISMATCH);
            }
            return loadCatalog(userId, workspaceOf(CallerScope.tenantOrLocal(), userId));
        }
        Session session = auth.authenticate(token).toCompletableFuture().join();
        if (!userId.equals(session.login())) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的目录");
        }
        return loadCatalog(userId, session.workspace());
    }

    /**
     * 装载用户目录和工作区里的规则与技能。工作区空白时只读用户目录。
     *
     * @param userId 用户 id
     * @param workspace 工作区绝对路径，可以是空白
     * @return 合并后的目录
     */
    private Catalog loadCatalog(String userId, String workspace) {
        Path userDirectory = auth.directories().userDirectory(userId);
        Path workspaceDirectory = workspace == null || workspace.isBlank() ? null : Path.of(workspace).resolve("jasagent");
        return new LocalCatalogLoader().load(workspaceDirectory, userDirectory).toCompletableFuture().join();
    }

    /**
     * 读取该租户用户已经写入 MySQL 的工作区。没有账本时是空串。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     * @return 工作区路径
     */
    private String workspaceOf(String tenantId, String userId) {
        if (ledger == null) {
            return "";
        }
        String path = ledger.workspace(tenantId, userId);
        return path == null ? "" : path;
    }

    /**
     * 把会话文档写入 MySQL。没有账本时仍写用户目录里的 json，供没有本机库的直接调用使用。
     * 副作用：覆盖这场会话的文档。
     *
     * @param stored 含 sessionId 和 userId 的会话文档
     */
    private void storeSession(Map<String, Object> stored) {
        String sessionId = String.valueOf(stored.get("sessionId"));
        String userId = String.valueOf(stored.get("userId"));
        if (ledger == null) {
            writeJson(conversationFile(userId, sessionId), stored);
            return;
        }
        String created = stored.get("createdAt") == null ? Instant.now().toString() : String.valueOf(stored.get("createdAt"));
        try {
            ledger.save(
                    CallerScope.tenantOrLocal(),
                    sessionId,
                    userId,
                    String.valueOf(stored.get("title")),
                    SessionModelCatalog.model(stored),
                    SessionModelCatalog.provider(stored),
                    Boolean.TRUE.equals(stored.get("titleCustom")),
                    created,
                    Instant.now().toString(),
                    JSON.writeValueAsString(stored));
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.INTERNAL, "会话无法写入", null, ex);
        }
    }

    /**
     * 取出或创建会话文档里的纠正列表。
     *
     * @param stored 会话文档
     * @return 可变列表
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> correctionsIn(Map<String, Object> stored) {
        Object value = stored.get("corrections");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        stored.put("corrections", created);
        return created;
    }

    /**
     * 记下这次工具参数，键是租户、用户和会话。
     *
     * @param exec 当前执行
     * @param name 工具名
     * @param arguments 参数原文
     */
    private void noteTool(Exec exec, String name, String arguments) {
        String key = scopeKey(exec.tenantId, exec.userId, exec.sessionId);
        toolNotes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(name + "\n" + (arguments == null ? "" : arguments));
    }

    /**
     * 拼隔离键。三段用换行分开，避免简单拼接撞车。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     * @param sessionId 会话 id
     * @return 缓存键
     */
    private static String scopeKey(String tenantId, String userId, String sessionId) {
        return (tenantId == null ? "" : tenantId) + "\n" + (userId == null ? "" : userId) + "\n" + (sessionId == null ? "" : sessionId);
    }

    /**
     * 该用户存放会话 json 的目录。
     *
     * @param userId 用户登录名
     * @return conversations 路径，不保证已经存在
     */
    private Path conversations(String userId) {
        return auth.directories().userDirectory(userId).resolve("conversations");
    }

    /**
     * 某个会话 json 的路径。编号必须是 UUID，避免路径穿越。
     *
     * @param userId 用户登录名
     * @param sessionId 会话编号
     * @return 会话文件路径
     */
    private Path conversationFile(String userId, String sessionId) {
        return conversations(userId).resolve(safeId(sessionId) + ".json");
    }

    /**
     * 某条纠正笔记的路径。会话编号和笔记编号都必须是 UUID。
     *
     * @param userId 用户登录名
     * @param sessionId 会话编号
     * @param noteId 笔记编号
     * @return json 文件路径
     */
    private Path correctionFile(String userId, String sessionId, String noteId) {
        return auth.directories().userDirectory(userId).resolve("corrections").resolve(safeId(sessionId)).resolve(safeId(noteId) + ".json");
    }

    /**
     * 读会话文件并核对 userId。对不上时按不能读取其他用户的会话拒绝。
     *
     * @param userId 期望的所有者
     * @param file 会话 json
     * @return 文件内容
     */
    private Map<String, Object> readOwned(String userId, Path file) {
        if (ledger != null) {
            String name = file.getFileName().toString();
            String sessionId = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
            CallerScope scope = CallerScope.current();
            if (scope != null && !scope.sessionId().isBlank() && !scope.sessionId().equals(sessionId)) {
                throw new AgentException(ErrorCode.AUTH, "网关会话与请求会话不一致");
            }
            if (scope != null && !userId.equals(scope.userId())) {
                throw new AgentException(ErrorCode.AUTH, GatewayHeaders.OWNER_MISMATCH);
            }
            try {
                String json = ledger.load(CallerScope.tenantOrLocal(), userId, sessionId);
                return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (AgentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new AgentException(ErrorCode.INTERNAL, "会话无法读取", null, ex);
            }
        }
        Map<String, Object> stored = readJson(file);
        if (!userId.equals(String.valueOf(stored.get("userId")))) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的会话");
        }
        return stored;
    }

    /**
     * 把 json 文件读成保持顺序的映射。文件不存在时也按无权读取拒绝，避免用缺失区分别人的会话。
     *
     * @param file 会话或笔记文件
     * @return 有序映射
     */
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

    /**
     * 创建父目录并把映射写成 UTF-8 JSON。写失败时抛出内部异常。
     * 副作用：覆写目标文件。
     *
     * @param file 目标路径
     * @param body 要写出的映射
     */
    private static void writeJson(Path file, Map<String, Object> body) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.INTERNAL, "会话无法写入", null, ex);
        }
    }

    /**
     * 取出会话里的 agents 列表。字段不是列表时新建一个空列表并放回文档。
     * 副作用：字段缺失或类型不对时向 stored 写入空列表。
     *
     * @param stored 会话文档
     * @return 可变的代理列表
     */
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

    /**
     * 取出会话里的 messages 列表。字段不是列表时新建一个空列表并放回文档。
     * 副作用：字段缺失或类型不对时向 stored 写入空列表。
     *
     * @param stored 会话文档
     * @return 可变的消息列表
     */
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

    /**
     * 从完整会话抽出列表用的摘要，模型字段按目录规则补默认值。
     *
     * @param stored 会话文档
     * @return 含 sessionId、title、model、modelProvider 和 createdAt 的映射
     */
    private static Map<String, Object> sessionSummary(Map<String, Object> stored) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", stored.get("sessionId"));
        row.put("title", stored.get("title"));
        row.put("model", SessionModelCatalog.model(stored));
        row.put("modelProvider", SessionModelCatalog.provider(stored));
        row.put("createdAt", stored.get("createdAt"));
        return row;
    }

    /**
     * 采用网关在请求里指定的执行编号。没有或格式不对时自己生成 UUID。
     *
     * @return 这次执行的编号
     */
    private static String assignedExecutionId() {
        String assigned = AssignedExecution.current();
        if (assigned != null && assigned.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,63}")) {
            return assigned;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 只接受 36 位的 UUID 文本，拒绝带路径分隔符的编号。
     *
     * @param id 会话或笔记编号
     * @return 通过校验的原编号
     */
    private static String safeId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的会话");
        }
        return id;
    }

    /**
     * 执行日志没打开时抛出“存储不可连接”，阻止继续读写执行。
     * 副作用：失败时抛出持久化异常，不修改执行状态。
     */
    private void requireJournal() {
        if (journal == null) {
            throw new AgentException(ErrorCode.PERSIST, "存储不可连接");
        }
    }

    /**
     * 复制进程环境，并用配置里非空的 JDBC 和检查点键覆盖。配置为 null 时只返回进程环境的副本。
     *
     * @param configuration 运行覆盖项，可以是 null
     * @return 新的环境映射，改它不会改进程环境
     */
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
        if (blank(env.get("JASAGENT_JDBC_URL")) && blank(env.get("JASAGENT_CHECKPOINT_JDBC_URL"))) {
            UragMysql mysql = UragMysql.discover();
            if (mysql != null) {
                env.put("JASAGENT_JDBC_URL", mysql.url());
                env.put("JASAGENT_JDBC_USER", mysql.user());
                env.put("JASAGENT_JDBC_PASSWORD", mysql.password());
                env.put("JASAGENT_CHECKPOINT", "mysql");
                env.put("JASAGENT_CHECKPOINT_JDBC_URL", mysql.url());
                env.put("JASAGENT_CHECKPOINT_JDBC_USER", mysql.user());
                env.put("JASAGENT_CHECKPOINT_JDBC_PASSWORD", mysql.password());
            }
        }
        return env;
    }

    /**
     * 判断环境值是否缺失或只有空白。
     *
     * @param value 环境变量值
     * @return 空白时为 true
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 空标题、“新会话”、“新会话”加数字，以及“会话”加数字，都视为还没手改，可以随后用第一条消息替换。
     *
     * @param title 请求或文档里的标题
     * @return 应自动起标题时为 true
     */
    private static boolean automaticTitle(String title) {
        if (title == null || title.isBlank()) {
            return true;
        }
        String trimmed = title.trim();
        return "新会话".equals(trimmed) || trimmed.matches("新会话\\s+\\d+") || trimmed.matches("会话\\s*\\d+");
    }

    /**
     * 会话标题是否已被用户改过。文档里有布尔的 titleCustom 时以它为准，否则看标题是不是自动标题。
     *
     * @param stored 会话文档
     * @return 手改过时为 true
     */
    private static boolean titleEdited(Map<String, Object> stored) {
        Object flag = stored.get("titleCustom");
        if (flag instanceof Boolean value) {
            return value;
        }
        Object title = stored.get("title");
        return !automaticTitle(title == null ? "" : String.valueOf(title));
    }

    /**
     * 把用户消息收成单行标题，换行改空格，最长 40 个字符。
     *
     * @param text 用户消息，可以是 null
     * @return 摘要；空白消息得到空串
     */
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

    /**
     * 标题超过上限时直接截断，不追加省略号。
     *
     * @param title 已经去掉多余空白的标题
     * @param max 最大字符数
     * @return 不超过 max 的标题
     */
    private static String limitTitle(String title, int max) {
        if (title.length() <= max) {
            return title;
        }
        return title.substring(0, max);
    }

    /**
     * 取出这次执行选定的供应商和模型。没选模型时返回 null，让路由自己决定。
     *
     * @param executionId 执行编号
     * @return 选择；执行不存在或模型为空时为 null
     */
    private WorkflowModels.Choice modelFor(String executionId) {
        Exec exec = executions.get(executionId);
        if (exec == null || exec.selectedModel == null || exec.selectedModel.isBlank()) {
            return null;
        }
        return new WorkflowModels.Choice(exec.selectedProvider, exec.selectedModel);
    }

    /**
     * 按工作区复用检查点控制器。工作流节点绑定到 runNode，追踪走内存追踪器。
     * 副作用：该工作区第一次使用时打开控制器并放进 controls。
     *
     * @param exec 提供工作区路径的执行
     * @return 该工作区的控制器
     */
    private CheckpointRunControl controlFor(Exec exec) {
        requireJournal();
        return controls.computeIfAbsent(exec.workspace, workspace -> CheckpointRunControl.open(
                Path.of(workspace),
                List.of(NodeTask.of("workflow", this::runNode)),
                traces,
                new MemoryTrace(),
                environment));
    }

    /**
     * 在虚拟线程里对执行发一次中断。返回的阶段只在中断调用抛错时异常完成，成功时一直不完成，用来把节点留在挂起。
     * 副作用：启动一条虚拟线程去调用 interrupt。
     *
     * @param exec 要中断的执行
     * @return 通常不会完成的阶段
     */
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

    /**
     * 批准后从检查点恢复。失败则回到挂起，记下错误码并追加 resume failed。
     * 副作用：改状态、错误码和数据库。
     *
     * @param exec 刚刚被允许继续的执行
     */
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

    /**
     * 用检查点快照回写状态、错误码、trace 和尚未有内容的输出，并落库。模型未配置且失败码是 LLM 时，把错误收成 OFFLINE。进入挂起时补一条 terminal SUSPENDED 日志。
     * 副作用：改执行字段并保存文档和状态列。
     *
     * @param exec 要回写的执行
     * @param snapshot 检查点控制器给出的快照
     */
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

    /**
     * 把执行过程文档写入日志。status 为真时同时更新状态列。
     * 副作用：写数据库。日志没打开时抛出存储不可连接。
     *
     * @param exec 要保存的执行
     * @param status 是否连同状态列一起更新
     */
    private void save(Exec exec, boolean status) {
        requireJournal();
        journal.saveDocument(exec.executionId, exec.sessionId, ExecutionJournal.writeJson(exec.state()));
        if (status) {
            journal.updateStatus(exec.executionId, exec.status);
        }
    }

    /**
     * 用数据库行覆盖内存里的状态和 trace。日志没打开或没有这条记录时不动内存。
     * 副作用：可能改 status、suspended、traceId 和 rootSpanId。
     *
     * @param exec 已经在内存中的执行
     */
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

    /**
     * 从日志和过程文档恢复执行。没有行时返回 null。文档为空时只恢复行上的身份、状态和 trace。
     *
     * @param executionId 执行编号
     * @return 恢复出的执行；库里没有时为 null
     */
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
            exec.tenantId = GatewayHeaders.TENANT_LOCAL;
            exec.status = row.status();
            exec.traceId = row.otelTraceId() == null ? "" : row.otelTraceId();
            exec.rootSpanId = row.otelRootSpanId() == null ? "" : row.otelRootSpanId();
            return exec;
        }
        return Exec.restore(ExecutionJournal.readJson(document), row);
    }

    /**
     * 先查内存，没有再从日志装载。仍然没有则抛出“执行不存在”。
     * 副作用：装载成功时放入内存表。
     *
     * @param executionId 执行编号
     * @return 执行对象
     */
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

    /**
     * 把执行上的错误码收成异常。错误码是 OFFLINE，或模型没配置且这次用了模型节点时，用 LLM 和“模型未配置”。
     *
     * @param exec 已经失败的执行
     * @return 交给节点阶段的异常，此方法本身不抛出
     */
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

    /**
     * 沿异常链找出错误码。模型未配置引起的 LLM 记为 OFFLINE，其他代理异常用它自己的错误码名，都没有时用 INTERNAL。
     *
     * @param ex 被收纳的运行时异常
     * @return 写入执行的错误码名字
     */
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

    /**
     * 成功、失败、取消、挂起和 OFFLINE 都算已经停住，中止时不再覆盖它们。
     *
     * @param status 当前状态名
     * @return 是终态时为 true
     */
    private static boolean terminalStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) || "SUSPENDED".equals(status) || "OFFLINE".equals(status);
    }

    /**
     * 时间线里是否已经有 system 消息，避免恢复或重入时再写一遍开场。
     *
     * @param exec 当前执行
     * @return 已有 system 时为 true
     */
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

    /**
     * 懒加载插件目录，并把通行证门交给插件。
     * 副作用：第一次调用时装载目录并记下引用。
     *
     * @return 插件目录
     */
    private PluginToolCatalog pluginTools() {
        if (pluginTools == null) {
            pluginTools = PluginToolCatalog.load(passGate);
        }
        return pluginTools;
    }

    /**
     * 已装载插件的名字。装载失败时返回空列表，不打断当前工具调用。
     *
     * @return 插件名；失败时是空列表
     */
    private List<String> pluginNames() {
        try {
            return pluginTools().names();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /**
     * 把工具参数拆成字符串映射。正文是 JSON 对象时按字段展开；不是对象时把原文同时记到 query、url、repo 和 arguments。
     *
     * @param raw 参数原文，可以是空白
     * @return 插件调用用的字符串映射
     */
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

    /**
     * 逐条复制映射，避免调用方改到执行内部的行。
     *
     * @param source 原始列表
     * @return 新列表，元素是新的有序映射
     */
    private static List<Map<String, Object>> copyMaps(List<Map<String, Object>> source) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : source) {
            rows.add(new LinkedHashMap<>(item));
        }
        return rows;
    }

    /**
     * 生成指定长度的十六进制串，供执行的 trace 和根 span 使用。
     *
     * @param length 字符数
     * @return 不含横线的十六进制串
     */
    private static String hex(int length) {
        String raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return raw.substring(0, length);
    }

    /**
     * 一次执行在内存中的全部状态，包括日志、时间线、通行证、所选模型和检查点运行次数。
     */
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
        /**
         * 这条执行所属的租户。空时按本地租户。另一台 agent 从文档里读回它。
         */
        private String tenantId = GatewayHeaders.TENANT_LOCAL;
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

        /**
         * 记下执行编号、用户、工作区绝对路径和会话令牌。状态从 ACCEPTED 开始，输出和错误码先是空串。
         * 副作用：只初始化这些身份字段。
         *
         * @param executionId 执行编号
         * @param userId 所有者登录名
         * @param workspace 工作区路径
         * @param token 创建这次执行时的会话令牌
         */
        private Exec(String executionId, String userId, String workspace, String token) {
            this.executionId = executionId;
            this.userId = userId;
            this.workspace = workspace;
            this.token = token;
        }

        /**
         * 往文本日志追加一行并换行。
         * 副作用：logs 增加这一行。
         *
         * @param line 不含换行的日志内容
         */
        private void append(String line) {
            logs.append(line).append('\n');
        }

        /**
         * 把当前字段收成可落库的过程文档。时间线在它自己的锁内复制。
         *
         * @return 有序映射，含身份、模型、输出、通行证、队列和时间线
         */
        private Map<String, Object> state() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("executionId", executionId);
            body.put("userId", userId);
            body.put("workspace", workspace);
            body.put("token", token);
            body.put("sessionId", sessionId);
            body.put("tenantId", tenantId);
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

        /**
         * 用数据库行的身份、状态和 trace，加上文档里的过程数据，重建执行。行上的 trace 优先于文档。
         *
         * @param state 之前保存的过程文档
         * @param row 数据库中的权威身份和状态
         * @return 新的执行对象，尚未放进内存表
         */
        private static Exec restore(Map<String, Object> state, ExecutionRow row) {
            Exec exec = new Exec(
                    row.executionId(),
                    row.userId(),
                    row.workspacePath(),
                    text(state.get("token")));
            exec.sessionId = row.sessionId();
            String tenant = text(state.get("tenantId"));
            exec.tenantId = tenant.isBlank() ? GatewayHeaders.TENANT_LOCAL : tenant;
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

        /**
         * 把文档里的值收成字符串，null 当成空串。
         *
         * @param value 文档字段
         * @return 文本
         */
        private static String text(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        /**
         * 把文档里的数字收成 int。数字类型直接取整，空白当成 0，其他文本按十进制解析。
         *
         * @param value 运行次数或工具序号
         * @return 整数
         */
        private static int number(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value == null || String.valueOf(value).isBlank()) {
                return 0;
            }
            return Integer.parseInt(String.valueOf(value));
        }

        /**
         * 把文档里的列表收成字符串列表。不是列表时得到空列表，元素为 null 时收成空串。
         *
         * @param value 队列、工具或评估字段
         * @return 新的字符串列表
         */
        private static List<String> strings(Object value) {
            List<String> rows = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    rows.add(item == null ? "" : String.valueOf(item));
                }
            }
            return rows;
        }

        /**
         * 把文档里的映射列表收成有序映射。不是映射的元素跳过，不是列表时得到空列表。
         *
         * @param value 检索、通行证、代理或时间线字段
         * @return 新的映射列表
         */
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

    /**
     * 给上下文窗口用的占位对话模型。没有真实模型时，补全和向量都直接失败，避免把失败文本当成已发布摘要。
     */
    private static final class OfflineChatModel implements dev.jasagent.api.llm.ChatModel {
        /**
         * 拒绝补全，错误是模型未配置。
         *
         * @param request 上下文窗口的补全请求，可以是 null
         * @return 以“模型未配置”失败的阶段
         */
        @Override
        public CompletionStage<ChatResponse> complete(dev.jasagent.api.llm.ChatRequest request) {
            String executionId = request == null ? null : request.executionId();
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", executionId, null));
        }

        /**
         * 拒绝向量化，错误是模型未配置。
         *
         * @param text 要向量化的文本，当前不使用
         * @param executionId 写进异常的执行编号
         * @return 以“模型未配置”失败的阶段
         */
        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", executionId, null));
        }
    }

    /**
     * 不落盘的追踪写入器。事件被接受但不保存，读取日志时返回空串。
     */
    private static final class MemoryTrace implements TraceWriter {
        /**
         * 丢掉这条追踪事件，不写工作区。
         *
         * @param workspace 本来要写入的工作区，当前不使用
         * @param event 追踪事件，当前不使用
         * @return 已完成的空阶段
         */
        @Override
        public CompletionStage<Void> event(Path workspace, TraceEvent event) {
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 不保存追踪日志，读取时始终给出空串。
         *
         * @param workspace 工作区，当前不使用
         * @param executionId 执行编号，当前不使用
         * @return 已完成的空串
         */
        @Override
        public CompletionStage<String> readLog(Path workspace, String executionId) {
            return CompletableFuture.completedFuture("");
        }
    }
}
