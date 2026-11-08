package dev.jasagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.Embedding;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.TokenUsage;
import dev.jasagent.api.llm.ToolCall;
import dev.jasagent.api.workflow.CheckpointStore;
import dev.jasagent.api.workflow.CorrectionNote;
import dev.jasagent.api.workflow.EvalResult;
import dev.jasagent.api.workflow.Evaluator;
import dev.jasagent.api.workflow.PluginHost;
import dev.jasagent.api.workflow.RetrievalResult;
import dev.jasagent.api.workflow.Retriever;
import dev.jasagent.api.workflow.SubagentRunner;
import dev.jasagent.api.workflow.ToolInvoker;
import dev.jasagent.api.workflow.ToolResult;
import dev.jasagent.api.workflow.WorkflowDocument;
import dev.jasagent.api.workflow.WorkflowResult;
import dev.jasagent.api.workflow.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowReactTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unknownNodeTypeRejected() {
        AgentException error = assertThrows(AgentException.class, () -> WorkflowLoader.parse("""
                {"id":"bad","nodes":[{"id":"x","type":"nope"}]}
                """));
        assertEquals(ErrorCode.WORKFLOW, error.code());
    }

    @Test
    void exampleWorkflowLoads() {
        WorkflowDocument document = WorkflowLoader.parse(WorkflowLoader.exampleAnswerWithTool());
        assertEquals("answer-with-tool", document.id());
        assertEquals(List.of("find", "act", "grade", "done"), document.nodeIds());
    }

    @Test
    void reactMessageOrder() {
        ScriptedChat chat = new ScriptedChat();
        chat.responses.add(answer("final text"));
        JsonWorkflowEngine engine = engine(chat, new RecordingTools(), okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        join(engine.execute(document("""
                {"id":"order","nodes":[{"id":"act","type":"react","maxSteps":2}]}
                """), runWithContext()));
        List<ChatMessage> messages = chat.seen.get(0);
        assertEquals("system", messages.get(0).role());
        assertTrue(messages.get(0).content().contains("RULE"));
        assertTrue(messages.get(0).content().contains("SKILL"));
        assertTrue(messages.get(0).content().contains("BOARD"));
        assertTrue(messages.get(0).content().contains("exec-order"));
        assertTrue(messages.get(1).content().contains("SUM"));
        assertTrue(messages.get(messages.size() - 2).content().contains("NOTE"));
        assertEquals("user", messages.get(messages.size() - 1).role());
        assertTrue(messages.get(messages.size() - 1).content().contains("CURRENT"));
    }

    @Test
    void reactCompletesOneToolCall() {
        ScriptedChat chat = new ScriptedChat();
        chat.responses.add(toolCall("clock"));
        chat.responses.add(answer("clock done"));
        RecordingTools tools = new RecordingTools();
        JsonWorkflowEngine engine = engine(chat, tools, okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        WorkflowResult result = join(engine.execute(document("""
                {"id":"once","nodes":[{"id":"act","type":"react","maxSteps":4,"tools":["clock"]}]}
                """), runPlain("hello")));
        assertEquals("SUCCEEDED", result.status());
        assertEquals("clock done", result.output());
        assertEquals(1, tools.count("clock"));
    }

    @Test
    void maxStepsFails() {
        ScriptedChat chat = new ScriptedChat();
        chat.responses.add(toolCall("clock"));
        chat.responses.add(toolCall("clock"));
        JsonWorkflowEngine engine = engine(chat, new RecordingTools(), okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        WorkflowResult result = join(engine.execute(document("""
                {"id":"limit","nodes":[{"id":"act","type":"react","maxSteps":2,"tools":["clock"]}]}
                """), runPlain("hello")));
        assertEquals("FAILED", result.status());
        assertEquals("WORKFLOW_MAX_STEPS", result.errorCode());
    }

    @Test
    void groundingAddsNote() {
        ScriptedChat chat = new ScriptedChat();
        chat.responses.add(answer("the value is 99999"));
        chat.responses.add(answer("the value is 99999"));
        JsonWorkflowEngine engine = engine(chat, new RecordingTools(), okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        WorkflowResult result = join(engine.execute(document("""
                {"id":"ground","nodes":[{"id":"act","type":"react","maxSteps":4,"grounding":true}]}
                """), runPlain("hello")));
        assertEquals(1, result.notes().stream().filter(note -> "GROUNDING".equals(note.source())).count());
    }

    @Test
    void groundingRetriesOnlyOnce() {
        ScriptedChat chat = new ScriptedChat();
        chat.responses.add(answer("the value is 99999"));
        chat.responses.add(answer("the value is 99999"));
        chat.responses.add(answer("should not be called"));
        JsonWorkflowEngine engine = engine(chat, new RecordingTools(), okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        join(engine.execute(document("""
                {"id":"ground","nodes":[{"id":"act","type":"react","maxSteps":6,"grounding":true}]}
                """), runPlain("hello")));
        assertEquals(2, chat.seen.size());
    }

    @Test
    void parallelToolCalls() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        ScriptedChat chat = new ScriptedChat() {
            @Override
            public CompletionStage<ChatResponse> complete(ChatRequest request) {
                if (modelCalls.incrementAndGet() == 2) {
                    assertEquals(0, bothStarted.getCount());
                }
                return super.complete(request);
            }
        };
        chat.responses.add(new ChatResponse("deepseek", "deepseek-flash",
                new ChatMessage("assistant", "", List.of(new ToolCall("1", "alpha", "{}"), new ToolCall("2", "beta", "{}"))),
                new TokenUsage(1, 1), 1, false));
        chat.responses.add(answer("joined"));
        ToolInvoker tools = (name, arguments, executionId) -> CompletableFuture.supplyAsync(() -> {
            bothStarted.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return new ToolResult(name + "-ok", true);
        });
        JsonWorkflowEngine engine = engine(chat, tools, okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        CompletableFuture<WorkflowResult> future = engine.execute(document("""
                {"id":"par","nodes":[{"id":"act","type":"react","maxSteps":4,"tools":["alpha","beta"]}]}
                """), runPlain("hello")).toCompletableFuture();
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
        assertFalse(future.isDone());
        release.countDown();
        WorkflowResult result = future.get(5, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", result.status());
    }

    @Test
    void conditionStringEquals() {
        WorkflowResult result = runCondition("eq", "yes", "yes");
        assertEquals("taken-yes", result.output());
    }

    @Test
    void conditionNumericThreshold() {
        WorkflowResult result = runCondition("gte", "5", "10");
        assertEquals("taken-yes", result.output());
    }

    @Test
    void conditionBoolean() {
        WorkflowResult result = runCondition("bool", "", "true");
        assertEquals("taken-yes", result.output());
    }

    @Test
    void emptyRetrieveDoesNotFail() {
        JsonWorkflowEngine engine = engine(new ScriptedChat(), new RecordingTools(), okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        WorkflowResult result = join(engine.execute(document("""
                {"id":"find-flow","nodes":[
                  {"id":"find","type":"retrieve","queryFrom":"input","topK":5,"scopes":["user","workspace"],"required":false,"next":"done"},
                  {"id":"done","type":"end"}
                ]}
                """), runPlain("query")));
        assertEquals("SUCCEEDED", result.status());
    }

    @Test
    void checkpointFailureSkipsNode() {
        RecordingTools tools = new RecordingTools();
        CheckpointStore checkpoints = (executionId, nodeId, stateJson) -> {
            if ("second".equals(nodeId)) {
                return CompletableFuture.failedFuture(new AgentException(ErrorCode.PERSIST, "commit failed", executionId, null));
            }
            return CompletableFuture.completedFuture(null);
        };
        JsonWorkflowEngine engine = engine(new ScriptedChat(), tools, checkpoints, emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        WorkflowResult result = join(engine.execute(document("""
                {"id":"cp","nodes":[
                  {"id":"first","type":"tool","tool":"clock","arguments":"{}","next":"second"},
                  {"id":"second","type":"tool","tool":"clock","arguments":"{}"}
                ]}
                """), runPlain("hello")));
        assertEquals("FAILED", result.status());
        assertEquals("PERSIST", result.errorCode());
        assertEquals(1, tools.count("clock"));
    }

    @Test
    void pluginPhasesAroundTool() {
        ScriptedChat chat = new ScriptedChat();
        chat.responses.add(toolCall("clock"));
        chat.responses.add(answer("done"));
        List<String> phases = new ArrayList<>();
        PluginHost plugins = (pluginId, phase, executionId, nodeId) -> {
            phases.add(phase);
            return CompletableFuture.completedFuture(null);
        };
        JsonWorkflowEngine engine = engine(chat, new RecordingTools(), okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), plugins);
        join(engine.execute(document("""
                {"id":"plug","nodes":[{"id":"act","type":"react","maxSteps":4,"tools":["clock"],"plugins":["grounding.default"]}]}
                """), runPlain("hello")));
        assertTrue(phases.contains("onNodeStart"));
        assertTrue(phases.contains("onNodeEnd"));
        assertTrue(phases.indexOf("afterModel") < phases.indexOf("beforeTool"));
        assertTrue(phases.indexOf("beforeTool") < phases.indexOf("afterTool"));
    }

    @Test
    void subagentsDefaultIsReactOnly() throws Exception {
        List<String> children = new ArrayList<>();
        SubagentRunner runner = (executionId, childWorkflowJson, parts) -> {
            children.add(childWorkflowJson);
            return CompletableFuture.completedFuture(List.of("child-ok"));
        };
        JsonWorkflowEngine engine = engine(new ScriptedChat(), new RecordingTools(), okCheckpoints(), emptyRetriever(), passingEval(), runner, noopPlugins());
        join(engine.execute(document("""
                {"id":"fan","nodes":[{"id":"split","type":"subagents","next":"done"},{"id":"done","type":"end"}]}
                """), runPlain("one")));
        JsonNode child = mapper.readTree(children.get(0));
        assertEquals(1, child.get("nodes").size());
        assertEquals("react", child.get("nodes").get(0).get("type").asText());
    }

    private WorkflowResult runCondition(String compare, String right, String input) {
        JsonWorkflowEngine engine = engine(new ScriptedChat(), new RecordingTools(), okCheckpoints(), emptyRetriever(), passingEval(), noopSubagents(), noopPlugins());
        String rightField = right.isBlank() ? "" : ",\"right\":\"" + right + "\"";
        return join(engine.execute(document("""
                {"id":"branch","nodes":[
                  {"id":"check","type":"condition","compare":"%s","left":"$previous"%s,"then":"yesNode","else":"noNode"},
                  {"id":"yesNode","type":"end","output":"taken-yes"},
                  {"id":"noNode","type":"end","output":"taken-no"}
                ]}
                """.formatted(compare, rightField)), runPlain(input)));
    }

    private static JsonWorkflowEngine engine(
            ChatModel model,
            ToolInvoker tools,
            CheckpointStore checkpoints,
            Retriever retriever,
            Evaluator evaluator,
            SubagentRunner subagents,
            PluginHost plugins) {
        return new JsonWorkflowEngine(model, tools, checkpoints, retriever, evaluator, subagents, plugins);
    }

    private static WorkflowDocument document(String json) {
        return WorkflowLoader.parse(json);
    }

    private static WorkflowRun runPlain(String input) {
        return new WorkflowRun("exec-1", "exec-1", "user-a", input, "", "", "", "", List.of(), new ArrayList<>(), 8000);
    }

    private static WorkflowRun runWithContext() {
        return new WorkflowRun(
                "exec-order",
                "sess-1",
                "user-a",
                "CURRENT",
                "RULE",
                "SKILL",
                "BOARD",
                "SUM",
                List.of(ChatMessage.text("user", "old-user"), ChatMessage.text("assistant", "old-assistant")),
                new ArrayList<>(List.of(new CorrectionNote("n1", "sess-1", "exec-order", Instant.parse("2026-11-08T00:00:00Z"), "USER", "NOTE"))),
                8000);
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse("deepseek", "deepseek-flash", ChatMessage.text("assistant", text), new TokenUsage(1, 1), 1, false);
    }

    private static ChatResponse toolCall(String name) {
        return new ChatResponse("deepseek", "deepseek-flash",
                new ChatMessage("assistant", "", List.of(new ToolCall("1", name, "{}"))),
                new TokenUsage(1, 1), 1, false);
    }

    private static CheckpointStore okCheckpoints() {
        return (executionId, nodeId, stateJson) -> CompletableFuture.completedFuture(null);
    }

    private static Retriever emptyRetriever() {
        return (query, topK, scopes, executionId) -> CompletableFuture.completedFuture(new RetrievalResult(List.of(), "quality-empty"));
    }

    private static Evaluator passingEval() {
        return (name, executionId, output) -> CompletableFuture.completedFuture(new EvalResult(true, "ok"));
    }

    private static SubagentRunner noopSubagents() {
        return (executionId, childWorkflowJson, parts) -> CompletableFuture.completedFuture(List.of());
    }

    private static PluginHost noopPlugins() {
        return (pluginId, phase, executionId, nodeId) -> CompletableFuture.completedFuture(null);
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static class ScriptedChat implements ChatModel {
        final List<ChatResponse> responses = new ArrayList<>();
        final List<List<ChatMessage>> seen = new ArrayList<>();

        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            assertEquals(LlmTask.REACT, request.task());
            seen.add(List.copyOf(request.messages()));
            return CompletableFuture.completedFuture(responses.remove(0));
        }

        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }

    private static final class RecordingTools implements ToolInvoker {
        private final List<String> calls = new ArrayList<>();

        @Override
        public CompletionStage<ToolResult> call(String name, String argumentsJson, String executionId) {
            calls.add(name);
            return CompletableFuture.completedFuture(new ToolResult(name + "-ok", true));
        }

        int count(String name) {
            return (int) calls.stream().filter(name::equals).count();
        }
    }
}
