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

/**
 * 覆盖工作流解析、消息顺序、react 工具循环、条件、检索、评估、中断和检查点。
 */
class WorkflowReactTest {
    @TempDir
    Path temp;

    /**
     * 文档使用不支持的节点类型时，断言解析以工作流错误失败。
     */
    @Test
    void unknownNodeTypeRejected() {
        AgentException error = assertThrows(AgentException.class, () -> WorkflowParser.parse("""
                {"id":"bad","nodes":[{"id":"a","type":"loop"}]}
                """));
        assertEquals(ErrorCode.WORKFLOW, error.code());
    }

    /**
     * 断言示例工作流的标识、规则、技能、预算，以及检索、react、评估和结束节点的字段都按文档还原。
     */
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
        assertEquals(List.of(
                "clock",
                "list_directory",
                "glob_files",
                "grep_files",
                "search_files",
                "search_workspace",
                "read_file",
                "read_files",
                "run_shell_command",
                "rag.search"), act.tools());
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

    /**
     * 断言发给模型的消息依次是系统提示、摘要、上一轮用户和上一轮助手，系统提示含角色、规则、技能和未完成任务。
     */
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

    /**
     * 断言两条纠正紧挨在当前用户问题前面，后写的纠正更靠近问题。
     */
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

    /**
     * 模型先要时钟再给出正文时，断言时钟只执行一次，运行成功，输出是最终正文，思考为空。
     */
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

    /**
     * 模型回复带思考时，断言运行结果同时保留正文和思考。
     */
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

    /**
     * 模型一次要两个工具时，断言两个工具都进入执行后才进行下一步，并且运行成功。
     */
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

    /**
     * 模型每次都继续要工具时，断言在步数上限处失败，错误码是最大步数，模型调用次数等于上限。
     */
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

    /**
     * 一次工具往返后，断言插件阶段按开始、模型前后、工具前后、再次模型前后和结束出现。
     */
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

    /**
     * 插件失败策略是中断时，断言运行以插件错误失败，并且模型没有被调用。
     */
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

    /**
     * 插件失败但没有要求中断时，断言运行仍然成功，并把插件错误留在结果里。
     */
    @Test
    void pluginFailureDefaultsToContinue() {
        ChatModel chat = request -> CompletableFuture.completedFuture(reply("ok"));
        WorkflowRun run = join(engine(chat, null, failingPlugin(), null, null, null)
                .execute(request(pluginReact(null), "插件", blank())));
        assertEquals("SUCCEEDED", run.status());
        assertFalse(run.pluginErrors().isEmpty());
    }

    /**
     * 回答里的数字和引号对不上依据时，断言只重试一次模型，两次都留下依据核对来源，然后成功结束。
     */
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

    /**
     * 评估结果为假时，断言布尔条件走假分支，被跳过的工具没有执行。
     */
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

    /**
     * 条件比较名不在允许名单里时，断言解析以工作流错误失败。
     */
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

    /**
     * 上一输出与期望字符串相等时，断言走到 taken 工具，skipped 工具没有执行。
     */
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

    /**
     * 上一输出达到数字阈值时，断言走到 taken 工具，skipped 工具没有执行。
     */
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

    /**
     * 子流程没有指定子工作流时，断言交给运行器的文档只有一个 react 节点，父运行输出是子流程结果。
     */
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

    /**
     * 子流程写了子工作流标识时，断言该标识原样交给子流程运行器。
     */
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

    /**
     * 检索为空且节点不要求必须有结果时，断言检索执行了一次，运行仍然成功。
     */
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

    /**
     * 评估未通过且节点不要求必须通过时，断言运行成功，并把未通过写进结果。
     */
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

    /**
     * 遇到中断节点时断言先暂停且后续工具未执行；用另一引擎按同一请求恢复后，断言后续工具执行并成功。
     */
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

    /**
     * 后一个工具开始前，断言前一节点的检查点已经写入，且保存时间不晚于该工具开始之后。
     */
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
                "jdbc:sqlite:" + database.toAbsolutePath());
        WorkflowRun run = join(engine(null, tool, null, null, null, null).execute(workflowRequest));
        assertEquals("SUCCEEDED", run.status());
    }

    /**
     * 断言运行结果里的图描述带有 LangGraph4j 的类型和版本。
     */
    @Test
    void graphJsonComesFromLanggraphDsl() {
        WorkflowRun run = join(engine(null, null, null, null, null, null).execute(request("""
                {"id":"end-only","nodes":[{"id":"done","type":"end"}]}
                """, "结束", blank())));
        assertTrue(run.graphDsl().contains("\"type\":\"langgraph4j\""));
        assertTrue(run.graphDsl().contains("\"version\":\"1.0\""));
    }

    /**
     * 没有 JDBC 地址时，断言成功运行使用 MySQL 检查点保存器，并且没有创建请求里的本地库文件。
     */
    @Test
    void defaultCheckpointSaverIsMysql() {
        Path database = temp.resolve("unused-default.db");
        WorkflowRequest workflowRequest = new WorkflowRequest(
                "mysql-default-" + System.nanoTime(),
                "session-1",
                "user-1",
                database,
                "结束",
                WorkflowParser.parse("""
                        {"id":"end-only","nodes":[{"id":"done","type":"end"}]}
                        """),
                blank(),
                null);
        WorkflowRun run = join(engine(null, null, null, null, null, null).execute(workflowRequest));
        assertEquals("SUCCEEDED", run.status(), run.detail());
        assertEquals("org.bsc.langgraph4j.checkpoint.MySQLSaverV2", run.checkpointSaver());
        assertFalse(Files.exists(database));
    }

    /**
     * 地址是 PostgreSQL 但库不可达时，断言运行失败细节里出现 Postgres 保存器，并且没有创建本地库文件。
     */
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

    /**
     * 断言图、检查点和追踪类都能加载，Spring 的上下文和聊天模型类不存在。
     */
    @Test
    void classpathUsesLanggraphNotSpring() {
        assertLoads("org.bsc.langgraph4j.StateGraph");
        assertLoads("org.bsc.langgraph4j.dsl.JsonDslGenerator");
        assertLoads("org.bsc.langgraph4j.checkpoint.SQLiteSaverV2");
        assertLoads("org.bsc.langgraph4j.checkpoint.PostgresSaverV2");
        assertLoads("org.bsc.langgraph4j.checkpoint.MySQLSaverV2");
        assertLoads("org.bsc.langgraph4j.otel.OTELWrapCallTraceHook");
        assertMissing("org.springframework.context.ApplicationContext");
        assertMissing("org.springframework.ai.chat.model.ChatModel");
    }

    /**
     * 模型还被挡住时，断言执行方法立刻返回未完成的阶段，放开后运行成功。
     */
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

    /**
     * 模型返回失败时，断言运行记录本身存在，状态为失败，错误码是模型错误。
     */
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

    /**
     * 断言 react 节点发给模型的任务名就是 react。
     */
    @Test
    void reactTaskNameIsReact() {
        List<ModelRequest> seen = new ArrayList<>();
        join(engine(recordingChat(seen, reply("ok")), null, null, null, null, null)
                .execute(request(reactJson(1, false), "任务", blank())));
        assertEquals("react", seen.get(0).task());
    }

    /**
     * 跑一个结束节点后，断言追踪里出现名为 evaluateNode 的 span。
     */
    @Test
    void graphTraceSpanIsEvaluateNode() {
        List<SpanData> spans = new CopyOnWriteArrayList<>();
        SpanExporter exporter = new SpanExporter() {
            /**
             * 把这一批 span 收进测试列表。
             *
             * @param batch 导出器交来的 span
             * @return 表示导出成功的结果
             */
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                spans.addAll(batch);
                return CompletableResultCode.ofSuccess();
            }

            /**
             * 这个测试导出器没有单独缓冲，刷新直接算成功。
             *
             * @return 表示刷新成功的结果
             */
            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            /**
             * 关闭导出器。测试里没有需要释放的外部资源。
             *
             * @return 表示关闭成功的结果
             */
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

    /**
     * 用临时 SQLite 文件、新的执行编号和给定文档装一份可执行请求。
     *
     * @param json 工作流文档
     * @param input 用户输入
     * @param context 提示上下文
     * @return 指向该临时库的工作流请求
     */
    private WorkflowRequest request(String json, String input, PromptContext context) {
        Path database = temp.resolve("workflow-" + System.nanoTime() + ".db");
        return new WorkflowRequest(
                "exec-" + System.nanoTime(),
                "session-1",
                "user-1",
                database,
                input,
                WorkflowParser.parse(json),
                context,
                "jdbc:sqlite:" + database.toAbsolutePath());
    }

    /**
     * 用给定协作者装引擎。没给的协作者换成会拒绝模型、返回空工具、空插件、空检索、通过评估或空子流程的默认实现。
     *
     * @param chat 聊天入口；null 时换成不应被调用的失败实现
     * @param tools 工具入口；null 时返回空输出
     * @param plugins 插件入口；null 时什么都不做
     * @param retriever 检索入口；null 时返回空命中
     * @param evaluator 评估入口；null 时返回通过
     * @param subagents 子流程入口；null 时返回空文本
     * @return 装好的工作流引擎
     */
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

    /**
     * 做一个会把每次请求记下来、并固定返回同一条回复的聊天模型。
     *
     * @param seen 收集模型请求的列表，方法返回的模型会向其中追加
     * @param reply 每次都返回的回复
     * @return 记录请求的聊天模型
     */
    private static ChatModel recordingChat(List<ModelRequest> seen, ModelReply reply) {
        return request -> {
            seen.add(request);
            return CompletableFuture.completedFuture(reply);
        };
    }

    /**
     * 做一条没有工具调用的模型回复。
     *
     * @param content 回复正文
     * @return 工具列表为空的回复
     */
    private static ModelReply reply(String content) {
        return new ModelReply(content, List.of());
    }

    /**
     * 做一个每个阶段都失败的插件宿主。
     *
     * @return 调用即失败的插件宿主
     */
    private static PluginHost failingPlugin() {
        return (PluginCall call) -> CompletableFuture.failedFuture(new IllegalStateException("插件炸了"));
    }

    /**
     * 做一份角色、规则、技能、任务、摘要、近几轮和纠正都空着的上下文。
     *
     * @return 空白提示上下文
     */
    private static PromptContext blank() {
        return new PromptContext("", List.of(), List.of(), List.of(), "", List.of(), List.of());
    }

    /**
     * 做一份带角色、规则、技能、未完成任务、摘要、两轮对话和两条纠正的上下文。
     *
     * @return 用于检查消息顺序的提示上下文
     */
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

    /**
     * 返回一步、不核对依据的 react 文档。
     *
     * @return 工作流 JSON
     */
    private static String promptJson() {
        return reactJson(1, false);
    }

    /**
     * 拼一份带步数上限、依据核对开关和工具名单的 react 文档，后面接一个结束节点。
     *
     * @param maxSteps react 节点的最大步数
     * @param grounding 是否打开依据核对
     * @param tools 允许的工具名
     * @return 工作流 JSON
     */
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

    /**
     * 拼一份挂了审计插件的 react 文档，可以附上插件失败策略。
     *
     * @param pluginFailure 失败策略；null 表示不写该字段
     * @return 工作流 JSON
     */
    private static String pluginReact(String pluginFailure) {
        String failure = pluginFailure == null ? "" : ",\"pluginFailure\":\"" + pluginFailure + "\"";
        return """
                {"id":"plugin-run","nodes":[
                  {"id":"act","type":"react","maxSteps":4,"tools":["clock"],"grounding":false,"plugins":["audit"]%s},
                  {"id":"done","type":"end"}
                ]}
                """.formatted(failure);
    }

    /**
     * 拼一份先由工具给出值、再按比较选择 taken 或 skipped 的文档。
     *
     * @param compare 条件比较名
     * @param value 期望值
     * @return 工作流 JSON
     */
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

    /**
     * 找正文匹配的用户消息下标。
     *
     * @param messages 发给模型的消息
     * @param content 要找的用户正文
     * @return 下标；没有这条消息时为 -1
     */
    private static int indexOfUser(List<ChatMessage> messages, String content) {
        for (int index = 0; index < messages.size(); index++) {
            if ("user".equals(messages.get(index).role()) && content.equals(messages.get(index).content())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 断言这个可选布尔不是真。空值也视为满足。
     *
     * @param value 节点上的可选布尔
     */
    private static void assertNotEqualsTrue(Boolean value) {
        assertNotEqualsBooleanTrue(value);
    }

    /**
     * 断言可选布尔为 null 或假。
     *
     * @param value 要检查的布尔
     */
    private static void assertNotEqualsBooleanTrue(Boolean value) {
        assertTrue(value == null || !value);
    }

    /**
     * 断言类名能被当前类加载器加载，加载不到时让测试失败。
     *
     * @param className 期望存在的类名
     */
    private static void assertLoads(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException ex) {
            throw new AssertionError(className, ex);
        }
    }

    /**
     * 断言类名不在当前类路径上。
     *
     * @param className 期望不存在的类名
     */
    private static void assertMissing(String className) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(className));
    }

    /**
     * 在 30 秒内等到阶段结束。运行时异常和错误原样抛出，中断会恢复中断标记。
     *
     * @param <T> 阶段结果的类型
     * @param stage 要等待的异步阶段
     * @return 阶段成功时的结果
     */
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
