package dev.jasagent.app;

import dev.jasagent.agentpass.DisabledPassGate;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
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
import dev.jasagent.api.workflow.WorkflowResult;
import dev.jasagent.api.workflow.WorkflowRun;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.evaluation.BuiltinEvaluators;
import dev.jasagent.evaluation.TerminalEvaluation;
import dev.jasagent.rag.Corpus;
import dev.jasagent.rag.Hit;
import dev.jasagent.rag.RagResult;
import dev.jasagent.rag.ScopedSearch;
import dev.jasagent.tool.ClockTool;
import dev.jasagent.workflow.JsonWorkflowEngine;
import dev.jasagent.workflow.WorkflowLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalRuntime implements ExecutionApi {
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
            WorkflowDocument document = WorkflowLoader.parse(WorkflowLoader.exampleAnswerWithTool());
            if (workflowId != null && !workflowId.isBlank() && !"answer-with-tool".equals(workflowId)) {
                Path file = Path.of(exec.workspace).resolve("jasagent").resolve("workflows").resolve(workflowId + ".json");
                if (Files.isRegularFile(file)) {
                    document = WorkflowLoader.parse(Files.readString(file));
                }
            }
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
            var pass = new DisabledPassGate().acquire(new PassRequest(exec.userId, exec.executionId, name, null, arguments)).toCompletableFuture().join();
            exec.passes.add(name + " " + pass.decision().name() + " " + pass.reason());
            exec.append("pass " + name + " " + pass.decision());
            if (pass.decision() == PassDecision.DENY) {
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
        row.put("traceId", exec.traceId);
        return row;
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
        private final List<String> passes = new ArrayList<>();
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
