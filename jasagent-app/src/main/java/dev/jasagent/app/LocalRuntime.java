package dev.jasagent.app;

import dev.jasagent.agentpass.DisabledPassGate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.auth.Catalog;
import dev.jasagent.api.auth.RuleDocument;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.auth.SkillDocument;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.exec.ConsoleApi;
import dev.jasagent.api.exec.ExecutionApi;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.Embedding;
import dev.jasagent.api.llm.TokenUsage;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.ToolCallContext;
import dev.jasagent.api.workflow.RetrievalResult;
import dev.jasagent.api.workflow.ToolResult;
import dev.jasagent.api.workflow.WorkflowDocument;
import dev.jasagent.api.workflow.WorkflowNode;
import dev.jasagent.api.workflow.WorkflowResult;
import dev.jasagent.api.workflow.WorkflowRun;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.auth.LocalCatalogLoader;
import dev.jasagent.evaluation.BuiltinEvaluators;
import dev.jasagent.evaluation.TerminalEvaluation;
import dev.jasagent.rag.Corpus;
import dev.jasagent.rag.Hit;
import dev.jasagent.rag.RagResult;
import dev.jasagent.rag.ScopedSearch;
import dev.jasagent.tool.ClockTool;
import dev.jasagent.workflow.JsonWorkflowEngine;
import dev.jasagent.workflow.WorkflowLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalRuntime implements ExecutionApi, ConsoleApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LocalAuthService auth;
    private final Map<String, Exec> executions = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ChatModel model = new OfflineChatModel();

    public LocalRuntime(LocalAuthService auth) {
        this.auth = auth;
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
        rememberCapabilities(exec, loadDocument(session.workspace(), workflowId));
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
        Session session = auth.authenticate(token).toCompletableFuture().join();
        try {
            Corpus corpus = loadCorpus(auth.directories().userDirectory(userId), session.workspace());
            RagResult result = new ScopedSearch(corpus).search(query, scopes);
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Hit hit : result.hits()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sourceScope", hit.sourceScope());
                row.put("uri", hit.uri());
                row.put("docId", hit.docId());
                row.put("chunkId", hit.chunkId());
                row.put("start", hit.start());
                row.put("end", hit.end());
                row.put("contentHash", hit.contentHash());
                row.put("fusionScore", hit.fusionScore());
                row.put("rerankScore", hit.rerankScore());
                row.put("text", hit.text());
                hits.add(row);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reason", result.reason());
            body.put("hits", hits);
            return body;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.RAG, "检索失败", null, ex);
        }
    }

    private void run(Exec exec, String workflowId, String input) {
        exec.pendingWorkflow = workflowId;
        exec.pendingInput = input;
        exec.status = "RUNNING";
        exec.append("schedule sub-1 " + workflowId);
        long delay = delayMillis();
        long waited = 0;
        while (waited < delay) {
            if (exec.suspended) {
                exec.status = "SUSPENDED";
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exec.status = "SUSPENDED";
                return;
            }
            waited += 50;
        }
        if (exec.suspended) {
            exec.status = "SUSPENDED";
            return;
        }
        try {
            WorkflowDocument document = loadDocument(exec.workspace, workflowId);
            JsonWorkflowEngine engine = new JsonWorkflowEngine(
                    model,
                    (name, arguments, executionId) -> callTool(exec, name, arguments),
                    (executionId, nodeId, state) -> {
                        exec.append("node " + nodeId);
                        return CompletableFuture.completedFuture(null);
                    },
                    (query, topK, scopes, executionId) -> {
                        Map<String, Object> found = search(exec.userId, exec.token, query, scopes);
                        exec.retrievals.add(found);
                        exec.append("retrieval " + found.get("reason"));
                        return CompletableFuture.completedFuture(new RetrievalResult(List.of(), "quality-empty"));
                    },
                    (name, executionId, output) -> CompletableFuture.completedFuture(BuiltinEvaluators.toolSuccessRate(1, 1)),
                    (executionId, child, parts) -> CompletableFuture.completedFuture(List.of()),
                    (pluginId, phase, executionId, nodeId) -> CompletableFuture.completedFuture(null));
            WorkflowRun workflowRun = new WorkflowRun(exec.executionId, exec.executionId, exec.userId, input, "base", "research", "answer", "", List.of(), new ArrayList<>(), 8000);
            WorkflowResult result = engine.execute(document, workflowRun).toCompletableFuture().join();
            if (exec.suspended) {
                exec.status = "SUSPENDED";
                return;
            }
            exec.output = result.output() == null ? "" : result.output();
            var evaluation = TerminalEvaluation.afterTerminal(result.status(), exec.output).evaluation();
            exec.evaluations.add(evaluation.detail() + ":" + evaluation.passed());
            exec.status = TerminalEvaluation.apply(result.status() == null ? "SUCCEEDED" : result.status(), false, evaluation);
            exec.append("terminal " + exec.status);
            exec.queue.clear();
            exec.queue.add("sub-1: done");
        } catch (Exception ex) {
            if (exec.suspended) {
                exec.status = "SUSPENDED";
                return;
            }
            exec.status = "FAILED";
            exec.errorCode = ex instanceof AgentException agent ? agent.code().name() : ErrorCode.INTERNAL.name();
            exec.append("terminal FAILED " + exec.errorCode);
        }
    }

    private CompletableFuture<ToolResult> callTool(Exec exec, String name, String arguments) {
        if (!"clock".equals(name)) {
            Map<String, Object> recorded = findPass(exec, name, "tool");
            if (recorded == null) {
                recorded = addPass(exec, name, "tool");
            }
            exec.append("pass " + name + " " + recorded.get("decision"));
            if (PassDecision.DENY.name().equals(recorded.get("decision"))) {
                return CompletableFuture.completedFuture(new ToolResult("denied", false));
            }
        }
        if ("clock".equals(name)) {
            return new ClockTool(Clock.systemUTC()).call(new ToolCallContext(exec.userId, exec.executionId, arguments)).toCompletableFuture();
        }
        if ("rag.search".equals(name)) {
            Map<String, Object> found = search(exec.userId, exec.token, arguments == null ? "" : arguments, List.of("user", "workspace"));
            exec.retrievals.add(found);
            return CompletableFuture.completedFuture(new ToolResult(String.valueOf(found.get("hits")), true));
        }
        return CompletableFuture.completedFuture(new ToolResult("unknown", false));
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
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sessionId", stored.get("sessionId"));
                row.put("title", stored.get("title"));
                row.put("createdAt", stored.get("createdAt"));
                rows.add(row);
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", stored.get("sessionId"));
        body.put("title", stored.get("title"));
        body.put("rules", rules);
        body.put("skills", skills(userId, token));
        body.put("corrections", corrections(userId, sessionId));
        body.put("messages", messages(stored));
        body.put("budgetTokens", 8000);
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
                pass.put("reason", "user-" + decision.toLowerCase());
                exec.append("pass " + pass.get("name") + " " + decision);
                return view(exec);
            }
        }
        throw new AgentException(ErrorCode.WORKFLOW, "通行证不存在", executionId, null);
    }

    private void rememberCapabilities(Exec exec, WorkflowDocument document) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        LinkedHashSet<String> plugins = new LinkedHashSet<>();
        for (WorkflowNode node : document.nodes()) {
            if (node.tools() != null) {
                tools.addAll(node.tools());
            }
            if (node.tool() != null && !node.tool().isBlank()) {
                tools.add(node.tool());
            }
            if (node.plugins() != null) {
                plugins.addAll(node.plugins());
            }
            if (node.grounding()) {
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
        var pass = new DisabledPassGate().acquire(new PassRequest(
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

    private static WorkflowDocument loadDocument(String workspace, String workflowId) {
        try {
            WorkflowDocument document = WorkflowLoader.parse(WorkflowLoader.exampleAnswerWithTool());
            if (workflowId != null && !workflowId.isBlank() && !"answer-with-tool".equals(workflowId)) {
                Path file = Path.of(workspace == null ? "" : workspace).resolve("jasagent").resolve("workflows").resolve(workflowId + ".json");
                if (Files.isRegularFile(file)) {
                    document = WorkflowLoader.parse(Files.readString(file));
                }
            }
            return document;
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

    private static Corpus loadCorpus(Path userDirectory, String workspace) throws Exception {
        Corpus corpus = Corpus.empty();
        Path userDocs = userDirectory.resolve("rag").resolve("docs");
        Path workspaceDocs = Path.of(workspace == null ? "" : workspace).resolve("jasagent").resolve("rag").resolve("docs");
        if (Files.isDirectory(userDocs)) {
            corpus = corpus.plus(Corpus.index(userDocs, "user"));
        }
        if (Files.isDirectory(workspaceDocs)) {
            corpus = corpus.plus(Corpus.index(workspaceDocs, "workspace"));
        }
        return corpus;
    }

    private static long delayMillis() {
        String raw = System.getenv("JASAGENT_START_DELAY_MS");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        return Long.parseLong(raw.trim());
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

    private static final class OfflineChatModel implements ChatModel {
        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            String current = request.messages().isEmpty() ? "" : request.messages().get(request.messages().size() - 1).content();
            return CompletableFuture.completedFuture(new ChatResponse(
                    "offline",
                    "offline-answer",
                    ChatMessage.text("assistant", "已收到：" + current),
                    new TokenUsage(1, 1),
                    1,
                    false));
        }

        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "embed 未配置"));
        }
    }
}
