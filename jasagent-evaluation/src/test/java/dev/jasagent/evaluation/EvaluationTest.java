package dev.jasagent.evaluation;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.Embedding;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.TokenUsage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationTest {
    private static final String EXECUTION_ID = "exec-011";

    @Test
    void nonemptyPassesWhenTextRemains() {
        RecordingChatModel model = new RecordingChatModel();
        EvaluationOutcome outcome = join(engine(false, model).evaluate(request(
                "completion.nonempty", false, RunStatus.SUCCEEDED, "  hello  ")));
        assertEquals("completion.nonempty", outcome.result().evaluatorId());
        assertTrue(outcome.result().passed());
        assertEquals(1.0, outcome.result().score());
        assertEquals("nonempty", outcome.result().reason());
        assertEquals(0, model.requests.size());
    }

    @Test
    void nonemptyFailsWhenBlank() {
        EvaluationEngine evaluation = engine(false, new RecordingChatModel());
        for (String completion : new String[] {null, "", " \t\n "}) {
            EvaluationResult result = join(evaluation.evaluate(request(
                    "completion.nonempty", false, RunStatus.SUCCEEDED, completion))).result();
            assertFalse(result.passed());
            assertEquals(0.0, result.score());
            assertEquals("empty", result.reason());
        }
    }

    @Test
    void toolSuccessRatePassesWithoutCalls() {
        EvaluationResult result = join(engine(false, new RecordingChatModel()).evaluate(request(
                "tool.success.rate", false, RunStatus.SUCCEEDED, "ignored"))).result();
        assertTrue(result.passed());
        assertEquals(1.0, result.score());
        assertEquals("success=0;total=0", result.reason());
    }

    @Test
    void toolSuccessRatePassesWhenAllSucceed() {
        RecordingChatModel model = new RecordingChatModel();
        EvaluationResult result = join(engine(false, model).evaluate(request(
                "tool.success.rate",
                false,
                RunStatus.SUCCEEDED,
                "",
                new ToolObservation("clock", true),
                new ToolObservation("rag.search", true)))).result();
        assertTrue(result.passed());
        assertEquals(1.0, result.score());
        assertEquals("success=2;total=2", result.reason());
        assertEquals(0, model.requests.size());
    }

    @Test
    void toolSuccessRateFailsOnPartial() {
        EvaluationResult result = join(engine(false, new RecordingChatModel()).evaluate(request(
                "tool.success.rate",
                false,
                RunStatus.SUCCEEDED,
                "",
                new ToolObservation("clock", true),
                new ToolObservation("rag.search", false)))).result();
        assertFalse(result.passed());
        assertEquals(0.5, result.score());
        assertEquals("success=1;total=2", result.reason());
    }

    @Test
    void judgeDisabledDoesNotCallRouter() {
        RecordingChatModel model = new RecordingChatModel();
        EvaluationResult result = join(engine(false, model).evaluate(request(
                "llm.judge", false, RunStatus.SUCCEEDED, "answer"))).result();
        assertFalse(result.passed());
        assertEquals(0.0, result.score());
        assertEquals("judge-disabled", result.reason());
        assertEquals(0, model.requests.size());
    }

    @Test
    void judgeEnabledCallsJudgeTask() {
        RecordingChatModel model = new RecordingChatModel();
        model.response = verdict("{\"passed\":true,\"score\":1,\"reason\":\"ok\"}");
        join(engine(true, model).evaluate(request(
                "llm.judge",
                false,
                RunStatus.SUCCEEDED,
                "ship the answer",
                new ToolObservation("clock", true))));
        assertEquals(1, model.requests.size());
        ChatRequest chat = model.requests.get(0);
        assertEquals(LlmTask.JUDGE, chat.task());
        assertNull(chat.namedProvider());
        assertTrue(chat.tools().isEmpty());
        assertEquals(EXECUTION_ID, chat.executionId());
        assertEquals("system", chat.messages().get(0).role());
        assertEquals("user", chat.messages().get(1).role());
        String user = chat.messages().get(1).content();
        assertTrue(user.contains("ship the answer"));
        assertTrue(user.contains("clock"));
        assertTrue(user.contains("success"));
    }

    @Test
    void judgeJsonIsParsed() {
        RecordingChatModel model = new RecordingChatModel();
        model.response = verdict("{\"score\":0.25,\"reason\":\"thin\",\"passed\":false}");
        EvaluationResult result = join(engine(true, model).evaluate(request(
                "llm.judge", false, RunStatus.SUCCEEDED, "short"))).result();
        assertFalse(result.passed());
        assertEquals(0.25, result.score());
        assertEquals("thin", result.reason());
        assertEquals(EXECUTION_ID, result.executionId());
        assertEquals("llm.judge", result.evaluatorId());
    }

    @Test
    void judgeUnparseableFails() {
        RecordingChatModel model = new RecordingChatModel();
        model.response = verdict("我认为通过");
        EvaluationOutcome outcome = join(engine(true, model).evaluate(request(
                "llm.judge", false, RunStatus.SUCCEEDED, "answer")));
        assertNotNull(outcome);
        assertNotNull(outcome.result());
        assertFalse(outcome.result().passed());
        assertEquals(0.0, outcome.result().score());
        assertEquals("judge-unparseable", outcome.result().reason());
    }

    @Test
    void optionalFailureKeepsStatus() {
        EvaluationOutcome outcome = join(engine(false, new RecordingChatModel()).evaluate(request(
                "completion.nonempty", false, RunStatus.SUCCEEDED, "   ")));
        assertFalse(outcome.result().passed());
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    @Test
    void requiredFailureSetsFailed() {
        EvaluationOutcome outcome = join(engine(false, new RecordingChatModel()).evaluate(request(
                "tool.success.rate",
                true,
                RunStatus.SUCCEEDED,
                "",
                new ToolObservation("clock", false))));
        assertFalse(outcome.result().passed());
        assertEquals(RunStatus.FAILED, outcome.status());
    }

    @Test
    void passingEvaluationKeepsStatus() {
        EvaluationOutcome outcome = join(engine(false, new RecordingChatModel()).evaluate(request(
                "completion.nonempty", true, RunStatus.CANCELLED, "kept")));
        assertTrue(outcome.result().passed());
        assertEquals(RunStatus.CANCELLED, outcome.status());
    }

    @Test
    void afterTerminalRunsNonempty() {
        RecordingChatModel model = new RecordingChatModel();
        EvaluationEngine evaluation = engine(false, model);
        for (RunStatus status : List.of(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.SUSPENDED)) {
            EvaluationOutcome outcome = join(evaluation.afterTerminal(EXECUTION_ID, status, "ready"));
            assertEquals("completion.nonempty", outcome.result().evaluatorId());
            assertEquals(status, outcome.status());
        }
        EvaluationOutcome succeeded = join(evaluation.afterTerminal(EXECUTION_ID, RunStatus.SUCCEEDED, "ready"));
        assertTrue(succeeded.result().passed());
        assertEquals(1.0, succeeded.result().score());
        assertEquals(RunStatus.SUCCEEDED, succeeded.status());
    }

    @Test
    void afterTerminalEmptyDoesNotFailExecution() {
        RecordingChatModel model = new RecordingChatModel();
        EvaluationOutcome outcome = join(engine(false, model).afterTerminal(EXECUTION_ID, RunStatus.SUCCEEDED, " \n"));
        assertFalse(outcome.result().passed());
        assertEquals("empty", outcome.result().reason());
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(0, model.requests.size());
    }

    @Test
    void beforeTerminalRejects() {
        RecordingChatModel model = new RecordingChatModel();
        AgentException error = assertThrows(AgentException.class,
                () -> join(engine(false, model).afterTerminal(EXECUTION_ID, RunStatus.ACCEPTED, "early")));
        assertEquals(ErrorCode.INTERNAL, error.code());
        assertEquals(0, model.requests.size());
    }

    @Test
    void unknownEvaluatorDoesNotCallModel() {
        RecordingChatModel model = new RecordingChatModel();
        EvaluationResult result = join(engine(false, model).evaluate(request(
                "not.an.evaluator", true, RunStatus.SUCCEEDED, "answer"))).result();
        assertFalse(result.passed());
        assertEquals(0.0, result.score());
        assertEquals("unknown-evaluator", result.reason());
        assertEquals(0, model.requests.size());
    }

    @Test
    void judgeRouterFailurePropagates() {
        RecordingChatModel model = new RecordingChatModel();
        model.failure = new AgentException(ErrorCode.LLM, "judge down", EXECUTION_ID, null);
        AgentException error = assertThrows(AgentException.class,
                () -> join(engine(true, model).evaluate(request(
                        "llm.judge", true, RunStatus.SUCCEEDED, "answer"))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(EXECUTION_ID, error.executionId());
    }

    @Test
    void modulePomHasNoSpring() throws Exception {
        Path pom = Path.of("pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("jasagent-evaluation", "pom.xml");
        }
        String xml = Files.readString(pom).toLowerCase();
        assertFalse(xml.contains("spring"));
    }

    @Test
    void judgeEnabledOnlyAcceptsTrue() {
        assertTrue(EvaluationSettings.judgeEnabled(Map.of("jasagent.evaluation.judge-enabled", "true")));
        assertFalse(EvaluationSettings.judgeEnabled(Map.of()));
        assertFalse(EvaluationSettings.judgeEnabled(null));
        assertFalse(EvaluationSettings.judgeEnabled(Map.of("jasagent.evaluation.judge-enabled", "")));
        assertFalse(EvaluationSettings.judgeEnabled(Map.of("jasagent.evaluation.judge-enabled", "false")));
        assertFalse(EvaluationSettings.judgeEnabled(Map.of("jasagent.evaluation.judge-enabled", "TRUE")));
    }

    private static EvaluationEngine engine(boolean judgeEnabled, ChatModel model) {
        return new EvaluationEngine(judgeEnabled, model);
    }

    private static EvaluationRequest request(
            String evaluatorId,
            boolean required,
            RunStatus status,
            String completion,
            ToolObservation... tools) {
        return new EvaluationRequest(EXECUTION_ID, evaluatorId, required, status, completion, List.of(tools));
    }

    private static ChatResponse verdict(String content) {
        return new ChatResponse("deepseek", "deepseek-flash", ChatMessage.text("assistant", content), new TokenUsage(0, 0), 1, false);
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
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

    private static final class RecordingChatModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();
        private ChatResponse response;
        private RuntimeException failure;

        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            requests.add(request);
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("embed"));
        }
    }
}
