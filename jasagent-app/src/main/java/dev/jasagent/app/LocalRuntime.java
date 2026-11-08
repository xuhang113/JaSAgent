package dev.jasagent.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.api.auth.Catalog;
import dev.jasagent.api.auth.RuleDocument;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.auth.SkillDocument;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.exec.ConsoleApi;
import dev.jasagent.api.exec.ExecutionApi;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.TokenUsage;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.schedule.SubAgentRunner;
import dev.jasagent.api.task.WorkItem;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.auth.LocalCatalogLoader;
import dev.jasagent.core.ContextInput;
import dev.jasagent.core.ContextWindow;
import dev.jasagent.core.QueueAgentScheduler;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class LocalRuntime implements ExecutionApi, ConsoleApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LocalAuthService auth;
    private final PassGate passGate;
    private final QueueAgentScheduler scheduler;
    private final Map<String, Exec> executions = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public LocalRuntime(LocalAuthService auth) {
        this(auth, Map.of());
    }

    public LocalRuntime(LocalAuthService auth, Map<String, String> configuration) {
        this.auth = auth;
        this.passGate = PassSettings.create(configuration, userId -> auth.directories().userDirectory(userId));
        SubAgentRunner runner = item -> CompletableFuture.completedFuture(null);
        this.scheduler = new QueueAgentScheduler(configuration, runner);
    }

    @Override
    public Map<String, Object> submit(String userId, String token, String workflowId, String input) {
        Session session = auth.authenticate(token).toCompletableFuture().join();
        if (session.workspace() == null || session.workspace().isBlank()) {
            throw new AgentException(ErrorCode.WORKFLOW, "未选择工作区");
        }
        String id = UUID.randomUUID().toString();
        Exec exec = new Exec(id, userId, session.workspace(), session.token());
        executions.put(id, exec);
        exec.append("accepted " + id);
        exec.append("user " + userId);
        exec.append("workspace " + session.workspace());
        exec.queue.add("sub-1: " + workflowId);
        rememberCapabilities(exec, loadDefinition(session.workspace(), workflowId));
        scheduler.enqueue(new WorkItem(id, id, id, workflowId, input == null ? "" : input, workflowId, input == null ? "" : input))
                .toCompletableFuture()
                .join();
        workers.execute(() -> run(exec, workflowId, input == null ? "" : input));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", id);
        body.put("status", "ACCEPTED");
        return body;
    }

    @Override
    public List<Map<String, Object>> list(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Exec exec : executions.values()) {
            if (userId.equals(exec.userId)) {
                rows.add(summary(exec));
            }
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
        exec.suspended = true;
        exec.status = "SUSPENDED";
        exec.append("interrupt");
        return view(exec);
    }

    @Override
    public Map<String, Object> resume(String userId, String executionId) {
        Exec exec = owned(userId, executionId);
        if (!"SUSPENDED".equals(exec.status)) {
            return view(exec);
        }
        exec.suspended = false;
        exec.status = "RUNNING";
        exec.append("resume");
        if (exec.pendingWorkflow != null) {
            String workflowId = exec.pendingWorkflow;
            String input = exec.pendingInput;
            workers.execute(() -> run(exec, workflowId, input));
        }
        return view(exec);
    }

    @Override
    public Map<String, Object> search(String userId, String token, String query, List<String> scopes) {
        auth.authenticate(token).toCompletableFuture().join();
        return retrieval(query);
    }

    private void run(Exec exec, String workflowId, String input) {
        exec.pendingWorkflow = workflowId;
        exec.pendingInput = input;
        exec.status = "RUNNING";
        if (exec.suspended) {
            exec.status = "SUSPENDED";
            return;
        }
        try {
            WorkflowDefinition definition = loadDefinition(exec.workspace, workflowId);
            Path database = checkpoint(exec);
            LangGraphWorkflowEngine engine = new LangGraphWorkflowEngine(
                    request -> CompletableFuture.completedFuture(new ChatModel.ModelReply("已收到", List.of())),
                    (executionId, name, arguments) -> callTool(exec, name, arguments),
                    call -> holdPlugin(exec, call.pluginId()),
                    query -> CompletableFuture.completedFuture(List.of()),
                    query -> CompletableFuture.completedFuture(new Evaluator.EvaluationOutcome(true, "tool.success.rate")),
                    call -> CompletableFuture.completedFuture(""));
            WorkflowRequest request = new WorkflowRequest(
                    exec.executionId,
                    exec.executionId,
                    exec.userId,
                    database,
                    input,
                    definition,
                    new PromptContext("assistant", List.of(), List.of(), List.of(), "", List.of(), List.of()),
                    null);
            WorkflowRun result = engine.execute(request).toCompletableFuture().join();
            if (exec.suspended || pending(exec)) {
                finishSuspended(exec, result.output());
                return;
            }
            exec.output = result.output() == null ? "" : result.output();
            exec.evaluations.add("completion.nonempty:" + !exec.output.isBlank());
            exec.status = "FAILED".equals(result.status()) ? "FAILED" : "SUCCEEDED";
            exec.errorCode = result.errorCode() == null ? "" : result.errorCode();
            exec.append("terminal " + exec.status);
            exec.queue.clear();
            exec.queue.add("sub-1: done");
        } catch (Exception ex) {
            if (exec.suspended || pending(exec)) {
                finishSuspended(exec, exec.output);
                return;
            }
            exec.status = "FAILED";
            exec.errorCode = ex instanceof AgentException agent ? agent.code().name() : ErrorCode.INTERNAL.name();
            exec.append("terminal FAILED " + exec.errorCode);
        }
    }

    private static Path checkpoint(Exec exec) throws Exception {
        Path directory = Path.of(exec.workspace).resolve("jasagent").resolve("data");
        Files.createDirectories(directory);
        exec.runs += 1;
        return directory.resolve(exec.executionId + "-" + exec.runs + ".db");
    }

    private void finishSuspended(Exec exec, String output) {
        exec.suspended = true;
        exec.status = "SUSPENDED";
        if (output != null && !output.isBlank()) {
            exec.output = output;
        }
        exec.append("terminal SUSPENDED");
    }

    private static boolean pending(Exec exec) {
        for (Map<String, Object> pass : exec.passes) {
            if (PassDecision.PENDING.name().equals(pass.get("decision"))) {
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
        if (!"clock".equals(name)) {
            Map<String, Object> recorded = findPass(exec, name, "tool");
            if (recorded == null) {
                recorded = addPass(exec, name, "tool");
            }
            exec.append("pass " + name + " " + recorded.get("decision"));
            String decision = String.valueOf(recorded.get("decision"));
            if (PassDecision.PENDING.name().equals(decision)) {
                exec.suspended = true;
                exec.status = "SUSPENDED";
                return CompletableFuture.completedFuture("approval-required");
            }
            if (PassDecision.DENY.name().equals(decision)) {
                return CompletableFuture.completedFuture("denied");
            }
        }
        if ("clock".equals(name)) {
            return CompletableFuture.completedFuture(Instant.now().toString());
        }
        if ("rag.search".equals(name)) {
            exec.retrievals.add(retrieval(arguments));
            return CompletableFuture.completedFuture("retrieved");
        }
        return CompletableFuture.completedFuture("");
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
        if (exec == null || !userId.equals(exec.userId)) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的执行");
        }
        return exec;
    }

    private static Map<String, Object> summary(Exec exec) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("executionId", exec.executionId);
        row.put("status", exec.status);
        return row;
    }

    private static Map<String, Object> view(Exec exec) {
        Map<String, Object> row = summary(exec);
        row.put("output", exec.output);
        row.put("errorCode", exec.errorCode);
        row.put("queue", List.copyOf(exec.queue));
        row.put("logs", exec.logs.toString());
        row.put("retrievals", List.copyOf(exec.retrievals));
        row.put("passes", List.copyOf(exec.passes));
        row.put("evaluations", List.copyOf(exec.evaluations));
        row.put("tools", List.copyOf(exec.tools));
        row.put("plugins", List.copyOf(exec.plugins));
        row.put("traceId", exec.traceId);
        return row;
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
        stored.put("title", title == null || title.isBlank() ? "新会话" : title.trim());
        stored.put("createdAt", Instant.now().toString());
        stored.put("messages", new ArrayList<>());
        writeJson(conversationFile(userId, sessionId), stored);
        return sessionSummary(stored);
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
        return readOwned(userId, conversationFile(userId, sessionId));
    }

    @Override
    public Map<String, Object> appendMessage(String userId, String token, String sessionId, String text, String workflowId) {
        if (text == null || text.isBlank()) {
            throw new AgentException(ErrorCode.WORKFLOW, "消息不能为空");
        }
        Path file = conversationFile(userId, sessionId);
        Map<String, Object> stored = readOwned(userId, file);
        String chosen = workflowId == null || workflowId.isBlank() ? "answer-with-tool" : workflowId;
        Map<String, Object> created = submit(userId, token, chosen, text);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("text", text);
        message.put("executionId", created.get("executionId"));
        message.put("createdAt", Instant.now().toString());
        messages(stored).add(message);
        writeJson(file, stored);
        return message;
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
        new ContextWindow(new OfflineChatModel()).prepare(new ContextInput(
                sessionId,
                "assistant",
                "",
                List.of(),
                "",
                List.of(),
                List.of(),
                "",
                ContextWindow.DEFAULT_BUDGET_TOKENS)).toCompletableFuture().join();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", stored.get("sessionId"));
        body.put("title", stored.get("title"));
        body.put("rules", rules);
        body.put("skills", skills(userId, token));
        body.put("corrections", corrections(userId, sessionId));
        body.put("messages", messages(stored));
        body.put("budgetTokens", ContextWindow.DEFAULT_BUDGET_TOKENS);
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
                if ("ALLOW".equals(decision) && exec.pendingWorkflow != null && (exec.suspended || "SUSPENDED".equals(exec.status))) {
                    exec.suspended = false;
                    exec.status = "RUNNING";
                    String workflowId = exec.pendingWorkflow;
                    String resumedInput = exec.pendingInput;
                    workers.execute(() -> run(exec, workflowId, resumedInput));
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
        row.put("createdAt", stored.get("createdAt"));
        return row;
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的会话");
        }
        return id;
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
        private final String traceId = UUID.randomUUID().toString().replace("-", "");
        private volatile String status = "ACCEPTED";
        private volatile String output = "";
        private volatile String errorCode = "";
        private volatile boolean suspended;
        private volatile String pendingWorkflow;
        private volatile String pendingInput;
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
    }

    private static final class OfflineChatModel implements dev.jasagent.api.llm.ChatModel {
        @Override
        public CompletionStage<ChatResponse> complete(dev.jasagent.api.llm.ChatRequest request) {
            return CompletableFuture.completedFuture(new ChatResponse(
                    "offline",
                    "offline-answer",
                    ChatMessage.text("assistant", "已收到"),
                    new TokenUsage(1, 1),
                    1,
                    false));
        }

        @Override
        public CompletionStage<dev.jasagent.api.llm.Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "embed 未配置"));
        }
    }
}
