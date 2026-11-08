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
import dev.jasagent.api.workflow.EvalResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationTest {
    @Test
    void completionNonempty() {
        assertTrue(BuiltinEvaluators.completionNonempty("ok").passed());
        assertFalse(BuiltinEvaluators.completionNonempty("  ").passed());
    }

    @Test
    void toolSuccessRate() {
        assertTrue(BuiltinEvaluators.toolSuccessRate(2, 2).passed());
        assertFalse(BuiltinEvaluators.toolSuccessRate(1, 2).passed());
    }

    @Test
    void judgeDisabledDoesNotCallModel() {
        Scripted model = new Scripted();
        BuiltinEvaluators.judge(false, model, "answer");
        assertEquals(0, model.tasks.size());
    }

    @Test
    void judgeEnabledUsesJudgeRoute() {
        Scripted model = new Scripted();
        BuiltinEvaluators.judge(true, model, "answer");
        assertEquals(LlmTask.JUDGE, model.tasks.get(0));
    }

    @Test
    void terminalRunsCompletionCheck() {
        EvalResult result = TerminalEvaluation.afterTerminal("SUCCEEDED", "  ").evaluation();
        assertEquals("completion.nonempty", result.detail().startsWith("completion.nonempty") ? "completion.nonempty" : result.detail());
        assertFalse(result.passed());
        assertEquals("completion.nonempty", TerminalEvaluation.EVALUATOR);
    }

    @Test
    void optionalFailureKeepsSuccess() {
        assertEquals("SUCCEEDED", TerminalEvaluation.apply("SUCCEEDED", false, BuiltinEvaluators.completionNonempty(" ")));
    }

    @Test
    void requiredFailureBecomesFailed() {
        assertEquals("FAILED", TerminalEvaluation.apply("SUCCEEDED", true, BuiltinEvaluators.completionNonempty(" ")));
    }

    private static final class Scripted implements ChatModel {
        private final List<LlmTask> tasks = new ArrayList<>();

        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            tasks.add(request.task());
            return CompletableFuture.completedFuture(new ChatResponse("deepseek", "deepseek-flash", ChatMessage.text("assistant", "yes"), new TokenUsage(1, 1), 1, false));
        }

        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }
}
