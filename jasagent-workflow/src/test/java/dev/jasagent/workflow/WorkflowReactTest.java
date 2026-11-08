package dev.jasagent.workflow;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.workflow.ChatModel.ChatMessage;
import dev.jasagent.workflow.ChatModel.ModelReply;
import dev.jasagent.workflow.ChatModel.ModelRequest;
import dev.jasagent.workflow.ChatModel.ToolCall;
import dev.jasagent.workflow.Evaluator.EvaluationOutcome;
import dev.jasagent.workflow.PluginHost.PluginCall;
import dev.jasagent.workflow.PromptContext.CorrectionNote;
import dev.jasagent.workflow.PromptContext.Turn;
import dev.jasagent.workflow.Retriever.RetrievalHit;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowReactTest {
    @TempDir
    Path temp;

    @Test
    void unknownNodeTypeRejected() {
        AgentException error = assertThrows(AgentException.class, () -> WorkflowParser.parse("""
                {"id":"bad","nodes":[{"id":"a","type":"loop"}]}
                """));
        assertEquals(ErrorCode.WORKFLOW, error.code());
    }

    @Test
    void exampleAnswerWithToolParses() {
        WorkflowDefinition definition = WorkflowParser.parse(WorkflowParser.exampleAnswerWithTool());
        assertEquals("answer-with-tool", definition.id());
        assertEquals(List.of("base"), definition.rules());
        assertEquals(List.of("research"), definition.skills());
        assertEquals(8000, definition.budgetTokens());
        assertEquals(4, definition.nodes().size());

        WorkflowDefinition.NodeSpec find = definition.nodes().get(0);
        assertEquals("find", find.id());
        assertEquals("retrieve", find.type());
        assertEquals("input", find.queryFrom());
        assertEquals(5, find.topK());
        assertEquals(List.of("user", "workspace"), find.scopes());
        assertNotEqualsTrue(find.required());

        WorkflowDefinition.NodeSpec act = definition.nodes().get(1);
        assertEquals("act", act.id());
        assertEquals("react", act.type());
        assertEquals(6, act.maxSteps());
        assertEquals(List.of("clock", "rag.search"), act.tools());
        assertEquals(Boolean.TRUE, act.grounding());

        WorkflowDefinition.NodeSpec grade = definition.nodes().get(2);
        assertEquals("grade", grade.id());
        assertEquals("evaluate", grade.type());
        assertEquals("tool.success.rate", grade.evaluator());
        assertNotEqualsTrue(grade.required());

        WorkflowDefinition.NodeSpec done = definition.nodes().get(3);
        assertEquals("done", done.id());
        assertEquals("end", done.type());
    }

    @Test
    void modelMessagesFollowFixedOrder() {
        List<ModelRequest> seen = new ArrayList<>();
        WorkflowRun run = join(engine(recordingChat(seen, reply("ok")), null, null, null, null, null)
                .execute(request(promptJson(), "当前问题", richContext())));
        assertEquals("SUCCEEDED", run.status());
        List<ChatMessage> messages = seen.get(0).messages();
        assertEquals("system", messages.get(0).role());
        assertTrue(messages.get(0).content().contains("助手"));
        assertTrue(messages.get(0).content().contains("base"));
        assertTrue(messages.get(0).content().contains("research"));
        assertTrue(messages.get(0).content().contains("未完成"));
        assertEquals("summary", messages.get(1).role());
        assertEquals("摘要正文", messages.get(1).content());
        assertEquals("user", messages.get(2).role());
        assertEquals("上一句", messages.get(2).content());
        assertEquals("assistant", messages.get(3).role());
        assertEquals("上一答", messages.get(3).content());
    }

    @Test
    void correctionSitsImmediatelyBeforeUser() {
        List<ModelRequest> seen = new ArrayList<>();
        join(engine(recordingChat(seen, reply("ok")), null, null, null, null, null)
                .execute(request(promptJson(), "当前问题", richContext())));
        List<ChatMessage> messages = seen.get(0).messages();
        int userAt = -1;
        for (int index = 0; index < messages.size(); index++) {
            if ("user".equals(messages.get(index).role()) && "当前问题".equals(messages.get(index).content())) {
                userAt = index;
            }
        }
        assertTrue(userAt > 0);
        assertEquals("correction", messages.get(userAt - 1).role());
        assertEquals("再改口径", messages.get(userAt - 1).content());
        assertEquals("correction", messages.get(userAt - 2).role());
        assertEquals("先改口径", messages.get(userAt - 2).content());
    }

    @Test
    void reactCompletesOneToolCall() {
        AtomicInteger clocks = new AtomicInteger();
        ChatModel chat = request -> {
            if (request.messages().stream().noneMatch(message -> "tool".equals(message.role()))) {
                return CompletableFuture.completedFuture(new ModelReply("", List.of(new ToolCall("1", "clock", "{}"))));
            }
            return CompletableFuture.completedFuture(reply("ready"));
        };
        ToolCaller tool = (executionId, name, arguments) -> {
            if ("clock".equals(name)) {
                clocks.incrementAndGet();
            }
            return CompletableFuture.completedFuture("2026-11-08T00:00:00Z");
        };
        WorkflowRun run = join(engine(
                chat,
                tool,
                null,
                query -> CompletableFuture.completedFuture(List.of(new RetrievalHit("hit"))),
                query -> CompletableFuture.completedFuture(new EvaluationOutcome(true, "ok")),
                null).execute(request(WorkflowParser.exampleAnswerWithTool(), "现在几点", blank())));
        assertEquals(1, clocks.get());
        assertEquals("SUCCEEDED", run.status());
        assertEquals("ready", run.output());
        assertEquals("", run.thinking());
    }

    @Test
    void reactKeepsModelThinkingBesideReply() {
        ChatModel chat = request -> CompletableFuture.completedFuture(new ModelReply("ready", List.of(), "先想一步"));
        WorkflowRun run = join(engine(
                chat,
                null,
                null,
                query -> CompletableFuture.completedFuture(List.of(new RetrievalHit("hit"))),
                query -> CompletableFuture.completedFuture(new EvaluationOutcome(true, "ok")),
                null).execute(request(WorkflowParser.exampleAnswerWithTool(), "现在几点", blank())));
        assertEquals("SUCCEEDED", run.status());
        assertEquals("ready", run.output());
        assertEquals("先想一步", run.thinking());
    }

    @Test
    void parallelToolCallsJoinBeforeNextStep() {
        CountDownLatch entered = new CountDownLatch(2);
        AtomicInteger finished = new AtomicInteger();
        ChatModel chat = request -> {
            if (request.messages().stream().noneMatch(message -> "tool".equals(message.role()))) {
                return CompletableFuture.completedFuture(new ModelReply("", List.of(
                        new ToolCall("1", "clock", "{}"),
                        new ToolCall("2", "rag.search", "{}"))));
            }
            assertEquals(2, finished.get());
            return CompletableFuture.completedFuture(reply("done"));
        };
        ToolCaller tool = (executionId, name, arguments) -> CompletableFuture.supplyAsync(() -> {
            entered.countDown();
            try {
                if (!entered.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("工具没有并行");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            finished.incrementAndGet();
            return name;
        });
        WorkflowRun run = join(engine(chat, tool, null, null, null, null)
                .execute(request(reactJson(3, false, "clock", "rag.search"), "并行", blank())));
        assertEquals("SUCCEEDED", run.status());
        assertEquals(2, finished.get());
    }

    @Test
    void maxStepsFailsWithWorkflowMaxSteps() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel chat = request -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new ModelReply("", List.of(new ToolCall("1", "clock", "{}"))));
        };
        WorkflowRun run = join(engine(chat, (executionId, name, arguments) -> CompletableFuture.completedFuture("ok"), null, null, null, null)
                .execute(request(reactJson(2, false, "clock"), "继续", blank())));
        assertEquals("FAILED", run.status());
        assertEquals("WORKFLOW_MAX_STEPS", run.errorCode());
        assertEquals(2, calls.get());
    }

    @Test
    void pluginPhasesAroundModelAndTool() {
        List<String> phases = new ArrayList<>();
        ChatModel chat = request -> {
            if (request.messages().stream().noneMatch(message -> "tool".equals(message.role()))) {
                return CompletableFuture.completedFuture(new ModelReply("", List.of(new ToolCall("1", "clock", "{}"))));
            }
            return CompletableFuture.completedFuture(reply("ok"));
        };
        PluginHost plugins = call -> {
            phases.add(call.phase());
            return CompletableFuture.completedFuture(null);
        };
        join(engine(chat, (executionId, name, arguments) -> CompletableFuture.completedFuture("tick"), plugins, null, null, null)
                .execute(request(pluginReact(null), "阶段", blank())));
        assertEquals(List.of(
                "onNodeStart",
                "beforeModel",
                "afterModel",
                "beforeTool",
                "afterTool",
                "beforeModel",
                "afterModel",
                "onNodeEnd"), phases);
    }

    @Test
    void pluginFailureFailStopsNode() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel chat = request -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(reply("ok"));
        };
        WorkflowRun run = join(engine(chat, null, failingPlugin(), null, null, null)
                .execute(request(pluginReact("fail"), "插件", blank())));
        assertEquals("FAILED", run.status());
        assertEquals("PLUGIN", run.errorCode());
        assertEquals(0, calls.get());
    }

    @Test
    void pluginFailureDefaultsToContinue() {
        ChatModel chat = request -> CompletableFuture.completedFuture(reply("ok"));
        WorkflowRun run = join(engine(chat, null, failingPlugin(), null, null, null)
                .execute(request(pluginReact(null), "插件", blank())));
        assertEquals("SUCCEEDED", run.status());
        assertFalse(run.pluginErrors().isEmpty());
    }

    @Test
    void groundingRetriesOnlyOnce() {
        List<ModelRequest> seen = new ArrayList<>();
        ChatModel chat = request -> {
            seen.add(request);
            return CompletableFuture.completedFuture(reply("42 \"ghost\""));
        };
        WorkflowRun run = join(engine(chat, null, null, null, null, null)
                .execute(request(reactJson(4, true), "hello", blank())));
        assertEquals(2, seen.size());
        assertEquals(List.of("GROUNDING", "GROUNDING"), run.correctionSources());
        List<ChatMessage> second = seen.get(1).messages();
        int userAt = indexOfUser(second, "hello");
        assertEquals("correction", second.get(userAt - 1).role());
        assertEquals("SUCCEEDED", run.status());
    }

    @Test
    void conditionBooleanFalseSkipsBranch() {
        AtomicInteger taken = new AtomicInteger();
        ToolCaller tool = (executionId, name, arguments) -> {
            if ("taken".equals(name)) {
                taken.incrementAndGet();
            }
            return CompletableFuture.completedFuture("yes");
        };
        Evaluator evaluator = query -> CompletableFuture.completedFuture(new EvaluationOutcome(false, "no"));
        WorkflowRun run = join(engine(null, tool, null, null, evaluator, null)
                .execute(request("""
                        {"id":"branch","nodes":[
                          {"id":"grade","type":"evaluate","evaluator":"tool.success.rate","required":false},
                          {"id":"check","type":"condition","compare":"boolean","whenTrue":"taken","whenFalse":"done"},
                          {"id":"taken","type":"tool","tool":"taken"},
                          {"id":"done","type":"end"}
                        ]}
                        """, "判断", blank())));
        assertEquals(0, taken.get());
        assertEquals("SUCCEEDED", run.status());
    }

    @Test
    void conditionRejectsUnsupportedCompare() {
        AgentException error = assertThrows(AgentException.class, () -> WorkflowParser.parse("""
                {"id":"bad","nodes":[
                  {"id":"check","type":"condition","compare":"contains","value":"a","whenTrue":"done","whenFalse":"done"},
                  {"id":"done","type":"end"}
                ]}
                """));
        assertEquals(ErrorCode.WORKFLOW, error.code());
    }

    @Test
    void conditionStringEqualitySelectsBranch() {
        List<String> called = new ArrayList<>();
        ToolCaller tool = (executionId, name, arguments) -> {
            called.add(name);
            return CompletableFuture.completedFuture("emit".equals(name) ? "yes" : name);
        };
        join(engine(null, tool, null, null, null, null).execute(request(conditionJson("equals", "yes"), "相等", blank())));
        assertTrue(called.contains("taken"));
        assertFalse(called.contains("skipped"));
    }

    @Test
    void conditionNumericThresholdSelectsBranch() {
        List<String> called = new ArrayList<>();
        ToolCaller tool = (executionId, name, arguments) -> {
            called.add(name);
            return CompletableFuture.completedFuture("emit".equals(name) ? "10" : name);
        };
        join(engine(null, tool, null, null, null, null).execute(request(conditionJson("gte", "5"), "阈值", blank())));
        assertTrue(called.contains("taken"));
        assertFalse(called.contains("skipped"));
    }

    @Test
    void subagentsDefaultToReactOnlyWorkflow() {
        AtomicReference<String> child = new AtomicReference<>();
        SubagentRunner runner = call -> {
            child.set(call.workflowJson());
            return CompletableFuture.completedFuture("child-result");
        };
        WorkflowRun run = join(engine(null, null, null, null, null, runner).execute(request("""
                {"id":"split-run","nodes":[
                  {"id":"split","type":"subagents","tasks":["one"]},
                  {"id":"done","type":"end"}
                ]}
                """, "拆开", blank())));
        WorkflowDefinition childWorkflow = WorkflowParser.parse(child.get());
        assertEquals(1, childWorkflow.nodes().size());
        assertEquals("react", childWorkflow.nodes().get(0).type());
        assertEquals("child-result", run.output());
    }

    @Test
    void subagentsPassChildWorkflowId() {
        AtomicReference<String> childId = new AtomicReference<>();
        SubagentRunner runner = call -> {
            childId.set(call.childWorkflowId());
            return CompletableFuture.completedFuture("ok");
        };
        join(engine(null, null, null, null, null, runner).execute(request("""
                {"id":"split-run","nodes":[
                  {"id":"split","type":"subagents","childWorkflow":"child-flow","tasks":["one"]},
                  {"id":"done","type":"end"}
                ]}
                """, "拆开", blank())));
        assertEquals("child-flow", childId.get());
    }

    @Test
    void retrieveEmptyDoesNotFailWhenOptional() {
        AtomicInteger calls = new AtomicInteger();
        Retriever retriever = query -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(List.of());
        };
        WorkflowRun run = join(engine(null, null, null, retriever, null, null).execute(request("""
                {"id":"find-run","nodes":[
                  {"id":"find","type":"retrieve","queryFrom":"input","topK":5,"scopes":["user","workspace"],"required":false},
                  {"id":"done","type":"end"}
                ]}
                """, "查询", blank())));
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", run.status());
    }

    @Test
    void evaluateFailureDoesNotFailWhenOptional() {
        AtomicInteger calls = new AtomicInteger();
        Evaluator evaluator = query -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new EvaluationOutcome(false, "empty"));
        };
        WorkflowRun run = join(engine(null, null, null, null, evaluator, null).execute(request("""
                {"id":"grade-run","nodes":[
                  {"id":"grade","type":"evaluate","evaluator":"tool.success.rate","required":false},
                  {"id":"done","type":"end"}
                ]}
                """, "评估", blank())));
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", run.status());
        assertEquals(Boolean.FALSE, run.evaluationPassed());
    }

    @Test
    void interruptSuspendsAndResumeContinues() {
        AtomicInteger after = new AtomicInteger();
        ToolCaller tool = (executionId, name, arguments) -> {
            if ("after".equals(name)) {
                after.incrementAndGet();
            }
            return CompletableFuture.completedFuture(name);
        };
        String json = """
                {"id":"pause-run","nodes":[
                  {"id":"before","type":"tool","tool":"before"},
                  {"id":"pause","type":"interrupt"},
                  {"id":"after","type":"tool","tool":"after"},
                  {"id":"done","type":"end"}
                ]}
                """;
        WorkflowRequest firstRequest = request(json, "暂停", blank());
        LangGraphWorkflowEngine first = engine(null, tool, null, null, null, null);
        WorkflowRun suspended = join(first.execute(firstRequest));
        assertEquals("SUSPENDED", suspended.status());
        assertEquals(0, after.get());

        LangGraphWorkflowEngine second = engine(null, tool, null, null, null, null);
        WorkflowRun resumed = join(second.resume(firstRequest));
        assertEquals("SUCCEEDED", resumed.status());
        assertEquals(1, after.get());
    }

    @Test
    void checkpointCommittedBeforeNextNode() throws Exception {
        Path database = temp.resolve("checkpoint-visible.db");
        ToolCaller tool = (executionId, name, arguments) -> {
            if (!"probe".equals(name)) {
                return CompletableFuture.completedFuture("mark");
            }
            Instant started = Instant.now();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "select saved_at from LG4JCheckpoint where next_node_id = ?")) {
                    statement.setString(1, "probe");
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        Instant saved = Instant.parse(rows.getString(1));
                        assertFalse(saved.isAfter(started.plusMillis(5)));
                    }
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            return CompletableFuture.completedFuture("seen");
        };
        WorkflowRequest workflowRequest = new WorkflowRequest(
                "checkpoint-exec",
                "session-1",
                "user-1",
                database,
                "检查点",
                WorkflowParser.parse("""
                        {"id":"checkpoint-run","nodes":[
                          {"id":"mark","type":"tool","tool":"mark"},
                          {"id":"probe","type":"tool","tool":"probe"},
                          {"id":"done","type":"end"}
                        ]}
                        """),
                blank(),
                null);
        WorkflowRun run = join(engine(null, tool, null, null, null, null).execute(workflowRequest));
        assertEquals("SUCCEEDED", run.status());
    }

    @Test
    void graphJsonComesFromLanggraphDsl() {
        WorkflowRun run = join(engine(null, null, null, null, null, null).execute(request("""
                {"id":"end-only","nodes":[{"id":"done","type":"end"}]}
                """, "结束", blank())));
        assertTrue(run.graphDsl().contains("\"type\":\"langgraph4j\""));
        assertTrue(run.graphDsl().contains("\"version\":\"1.0\""));
    }

    @Test
    void defaultCheckpointSaverIsSqlite() {
        WorkflowRun run = join(engine(null, null, null, null, null, null).execute(request("""
                {"id":"end-only","nodes":[{"id":"done","type":"end"}]}
                """, "结束", blank())));
        assertEquals("org.bsc.langgraph4j.checkpoint.SQLiteSaverV2", run.checkpointSaver());
    }

    @Test
    void postgresUrlUsesPostgresSaver() {
        Path database = temp.resolve("should-not-exist.db");
        WorkflowRequest workflowRequest = new WorkflowRequest(
                "postgres-exec",
                "session-1",
                "user-1",
                database,
                "库",
                WorkflowParser.parse("""
                        {"id":"end-only","nodes":[{"id":"done","type":"end"}]}
                        """),
                blank(),
                "jdbc:postgresql://127.0.0.1:1/jasagent?user=jasagent&password=placeholder");
        WorkflowRun run = join(engine(null, null, null, null, null, null).execute(workflowRequest));
        assertEquals("FAILED", run.status());
        assertTrue(run.detail().contains("PostgresSaverV2"));
        assertFalse(Files.exists(database));
    }

    @Test
    void classpathUsesLanggraphNotSpring() {
        assertLoads("org.bsc.langgraph4j.StateGraph");
        assertLoads("org.bsc.langgraph4j.dsl.JsonDslGenerator");
        assertLoads("org.bsc.langgraph4j.checkpoint.SQLiteSaverV2");
        assertLoads("org.bsc.langgraph4j.checkpoint.PostgresSaverV2");
        assertLoads("org.bsc.langgraph4j.otel.OTELWrapCallTraceHook");
        assertMissing("org.springframework.context.ApplicationContext");
        assertMissing("org.springframework.ai.chat.model.ChatModel");
    }

    @Test
    void executeReturnsIncompleteStage() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ChatModel chat = request -> CompletableFuture.supplyAsync(() -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return reply("ok");
        });
        CompletionStage<WorkflowRun> stage = engine(chat, null, null, null, null, null)
                .execute(request(reactJson(1, false), "等待", blank()));
        assertFalse(stage.toCompletableFuture().isDone());
        release.countDown();
        assertEquals("SUCCEEDED", join(stage).status());
    }

    @Test
    void llmFailureIsNotNull() {
        ChatModel chat = request -> CompletableFuture.failedFuture(
                new AgentException(ErrorCode.LLM, "模型失败", null, null));
        WorkflowRun run = join(engine(chat, null, null, null, null, null)
                .execute(request(reactJson(1, false), "失败", blank())));
        assertNotNull(run);
        assertEquals("FAILED", run.status());
        assertEquals("LLM", run.errorCode());
    }

    @Test
    void reactTaskNameIsReact() {
        List<ModelRequest> seen = new ArrayList<>();
        join(engine(recordingChat(seen, reply("ok")), null, null, null, null, null)
                .execute(request(reactJson(1, false), "任务", blank())));
        assertEquals("react", seen.get(0).task());
    }

    @Test
    void graphTraceSpanIsEvaluateNode() {
        List<SpanData> spans = new CopyOnWriteArrayList<>();
        SpanExporter exporter = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                spans.addAll(batch);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(sdk);
        try {
            join(engine(null, null, null, null, null, null).execute(request("""
                    {"id":"end-only","nodes":[{"id":"done","type":"end"}]}
                    """, "追踪", blank())));
            provider.forceFlush().join(5, TimeUnit.SECONDS);
            assertTrue(spans.stream().anyMatch(span -> "evaluateNode".equals(span.getName())));
        } finally {
            provider.close();
            GlobalOpenTelemetry.resetForTest();
        }
    }

    private WorkflowRequest request(String json, String input, PromptContext context) {
        return new WorkflowRequest(
                "exec-" + System.nanoTime(),
                "session-1",
                "user-1",
                temp.resolve("workflow-" + System.nanoTime() + ".db"),
                input,
                WorkflowParser.parse(json),
                context,
                null);
    }

    private LangGraphWorkflowEngine engine(
            ChatModel chat,
            ToolCaller tools,
            PluginHost plugins,
            Retriever retriever,
            Evaluator evaluator,
            SubagentRunner subagents) {
        return new LangGraphWorkflowEngine(
                chat == null ? request -> CompletableFuture.failedFuture(new IllegalStateException("不应调用模型")) : chat,
                tools == null ? (executionId, name, arguments) -> CompletableFuture.completedFuture("") : tools,
                plugins == null ? call -> CompletableFuture.completedFuture(null) : plugins,
                retriever == null ? query -> CompletableFuture.completedFuture(List.of()) : retriever,
                evaluator == null ? query -> CompletableFuture.completedFuture(new EvaluationOutcome(true, "")) : evaluator,
                subagents == null ? call -> CompletableFuture.completedFuture("") : subagents);
    }

    private static ChatModel recordingChat(List<ModelRequest> seen, ModelReply reply) {
        return request -> {
            seen.add(request);
            return CompletableFuture.completedFuture(reply);
        };
    }

    private static ModelReply reply(String content) {
        return new ModelReply(content, List.of());
    }

    private static PluginHost failingPlugin() {
        return (PluginCall call) -> CompletableFuture.failedFuture(new IllegalStateException("插件炸了"));
    }

    private static PromptContext blank() {
        return new PromptContext("", List.of(), List.of(), List.of(), "", List.of(), List.of());
    }

    private static PromptContext richContext() {
        return new PromptContext(
                "助手",
                List.of("base"),
                List.of("research"),
                List.of("未完成"),
                "摘要正文",
                List.of(new Turn("user", "上一句"), new Turn("assistant", "上一答")),
                List.of(new CorrectionNote("n1", "USER", "先改口径"), new CorrectionNote("n2", "USER", "再改口径")));
    }

    private static String promptJson() {
        return reactJson(1, false);
    }

    private static String reactJson(int maxSteps, boolean grounding, String... tools) {
        StringBuilder toolList = new StringBuilder();
        for (int index = 0; index < tools.length; index++) {
            if (index > 0) {
                toolList.append(',');
            }
            toolList.append('"').append(tools[index]).append('"');
        }
        return """
                {"id":"react-run","nodes":[
                  {"id":"act","type":"react","maxSteps":%d,"tools":[%s],"grounding":%s},
                  {"id":"done","type":"end"}
                ]}
                """.formatted(maxSteps, toolList, grounding);
    }

    private static String pluginReact(String pluginFailure) {
        String failure = pluginFailure == null ? "" : ",\"pluginFailure\":\"" + pluginFailure + "\"";
        return """
                {"id":"plugin-run","nodes":[
                  {"id":"act","type":"react","maxSteps":4,"tools":["clock"],"grounding":false,"plugins":["audit"]%s},
                  {"id":"done","type":"end"}
                ]}
                """.formatted(failure);
    }

    private static String conditionJson(String compare, String value) {
        return """
                {"id":"compare-run","nodes":[
                  {"id":"emit","type":"tool","tool":"emit"},
                  {"id":"check","type":"condition","compare":"%s","value":"%s","whenTrue":"taken","whenFalse":"skipped"},
                  {"id":"taken","type":"tool","tool":"taken"},
                  {"id":"skipped","type":"tool","tool":"skipped"},
                  {"id":"done","type":"end"}
                ]}
                """.formatted(compare, value);
    }

    private static int indexOfUser(List<ChatMessage> messages, String content) {
        for (int index = 0; index < messages.size(); index++) {
            if ("user".equals(messages.get(index).role()) && content.equals(messages.get(index).content())) {
                return index;
            }
        }
        return -1;
    }

    private static void assertNotEqualsTrue(Boolean value) {
        assertNotEqualsBooleanTrue(value);
    }

    private static void assertNotEqualsBooleanTrue(Boolean value) {
        assertTrue(value == null || !value);
    }

    private static void assertLoads(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException ex) {
            throw new AssertionError(className, ex);
        }
    }

    private static void assertMissing(String className) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(className));
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
