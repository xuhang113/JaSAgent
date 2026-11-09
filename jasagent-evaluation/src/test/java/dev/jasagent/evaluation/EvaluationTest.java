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

/**
 * 核对非空完成、工具成功率、模型评判和终态后评估是否按约定改写运行状态。
 */
class EvaluationTest {
    /**
     * 这组用例固定使用的执行标识，用来确认结果没有串到别的执行。
     */
    private static final String EXECUTION_ID = "exec-011";

    /**
     * 完成文本去掉空白后仍有内容时得满分，且不调用模型。断言失败则本测试失败。
     */
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

    /**
     * 完成文本为空、空串或只有空白时记为零分，原因是空。断言失败则本测试失败。
     */
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

    /**
     * 没有工具调用时成功率视为满分，并写明成功数和总数都是零。断言失败则本测试失败。
     */
    @Test
    void toolSuccessRatePassesWithoutCalls() {
        EvaluationResult result = join(engine(false, new RecordingChatModel()).evaluate(request(
                "tool.success.rate", false, RunStatus.SUCCEEDED, "ignored"))).result();
        assertTrue(result.passed());
        assertEquals(1.0, result.score());
        assertEquals("success=0;total=0", result.reason());
    }

    /**
     * 全部工具调用成功时通过，且不会向模型发请求。断言失败则本测试失败。
     */
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

    /**
     * 两次工具调用只成功一次时不通过，分数是二分之一。断言失败则本测试失败。
     */
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

    /**
     * 评判开关关闭时直接返回未启用，不向模型发评判请求。断言失败则本测试失败。
     */
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

    /**
     * 评判开启时只发一次评判任务，用户消息里要包含完成文本、工具名和成功标记。断言失败则本测试失败。
     */
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

    /**
     * 模型返回的 JSON 字段顺序可以不同，仍要读出未通过、分数和原因。断言失败则本测试失败。
     */
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

    /**
     * 模型回复不是要求的 JSON 时，结局仍存在，但记为无法解析且零分。断言失败则本测试失败。
     */
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

    /**
     * 非必选评估未通过时保留原来的成功状态。断言失败则本测试失败。
     */
    @Test
    void optionalFailureKeepsStatus() {
        EvaluationOutcome outcome = join(engine(false, new RecordingChatModel()).evaluate(request(
                "completion.nonempty", false, RunStatus.SUCCEEDED, "   ")));
        assertFalse(outcome.result().passed());
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    /**
     * 必选的工具成功率未通过时，运行状态要改成失败。断言失败则本测试失败。
     */
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

    /**
     * 评估通过时保留原来的取消状态，不因为必选就把状态改掉。断言失败则本测试失败。
     */
    @Test
    void passingEvaluationKeepsStatus() {
        EvaluationOutcome outcome = join(engine(false, new RecordingChatModel()).evaluate(request(
                "completion.nonempty", true, RunStatus.CANCELLED, "kept")));
        assertTrue(outcome.result().passed());
        assertEquals(RunStatus.CANCELLED, outcome.status());
    }

    /**
     * 成功、失败、取消和挂起都允许做终态后的非空检查，有内容时保持原状态并通过。断言失败则本测试失败。
     */
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

    /**
     * 终态后的非空检查即使文本为空白也不把成功改成失败，并且不调用模型。断言失败则本测试失败。
     */
    @Test
    void afterTerminalEmptyDoesNotFailExecution() {
        RecordingChatModel model = new RecordingChatModel();
        EvaluationOutcome outcome = join(engine(false, model).afterTerminal(EXECUTION_ID, RunStatus.SUCCEEDED, " \n"));
        assertFalse(outcome.result().passed());
        assertEquals("empty", outcome.result().reason());
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(0, model.requests.size());
    }

    /**
     * 运行还只是已接收时不能做终态后评估，并报内部错误。断言失败则本测试失败。
     */
    @Test
    void beforeTerminalRejects() {
        RecordingChatModel model = new RecordingChatModel();
        AgentException error = assertThrows(AgentException.class,
                () -> join(engine(false, model).afterTerminal(EXECUTION_ID, RunStatus.ACCEPTED, "early")));
        assertEquals(ErrorCode.INTERNAL, error.code());
        assertEquals(0, model.requests.size());
    }

    /**
     * 不认识的评估器标识直接未通过，不向模型发请求。断言失败则本测试失败。
     */
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

    /**
     * 评判模型自己失败时，要把模型错误和执行标识原样传播出来。断言失败则本测试失败。
     */
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

    /**
     * 评估模块的构建描述里不能出现 Spring 依赖。断言失败则本测试失败。
     */
    @Test
    void modulePomHasNoSpring() throws Exception {
        Path pom = Path.of("pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("jasagent-evaluation", "pom.xml");
        }
        String xml = Files.readString(pom).toLowerCase();
        assertFalse(xml.contains("spring"));
    }

    /**
     * 评判开关只接受小写 true；缺省、空串、false 和大写 TRUE 都是关闭。断言失败则本测试失败。
     */
    @Test
    void judgeEnabledOnlyAcceptsTrue() {
        assertTrue(EvaluationSettings.judgeEnabled(Map.of("jasagent.evaluation.judge-enabled", "true")));
        assertFalse(EvaluationSettings.judgeEnabled(Map.of()));
        assertFalse(EvaluationSettings.judgeEnabled(null));
        assertFalse(EvaluationSettings.judgeEnabled(Map.of("jasagent.evaluation.judge-enabled", "")));
        assertFalse(EvaluationSettings.judgeEnabled(Map.of("jasagent.evaluation.judge-enabled", "false")));
        assertFalse(EvaluationSettings.judgeEnabled(Map.of("jasagent.evaluation.judge-enabled", "TRUE")));
    }

    /**
     * 按给定开关和模型组装评估引擎。
     *
     * @param judgeEnabled 是否启用模型评判
     * @param model 评判使用的对话模型
     * @return 供用例调用的引擎
     */
    private static EvaluationEngine engine(boolean judgeEnabled, ChatModel model) {
        return new EvaluationEngine(judgeEnabled, model);
    }

    /**
     * 组装一条使用固定执行标识的评估请求。
     *
     * @param evaluatorId 评估器标识
     * @param required 未通过时是否必须把运行标成失败
     * @param status 评估前的运行状态
     * @param completion 完成文本
     * @param tools 附带的工具观察，可以一个都不传
     * @return 可直接交给引擎的请求
     */
    private static EvaluationRequest request(
            String evaluatorId,
            boolean required,
            RunStatus status,
            String completion,
            ToolObservation... tools) {
        return new EvaluationRequest(EXECUTION_ID, evaluatorId, required, status, completion, List.of(tools));
    }

    /**
     * 包一条只含正文的模型回复，供评判解析测试使用。
     *
     * @param content 模型回复正文
     * @return 带零用量的回复
     */
    private static ChatResponse verdict(String content) {
        return new ChatResponse("deepseek", "deepseek-flash", ChatMessage.text("assistant", content), new TokenUsage(0, 0), 1, false);
    }

    /**
     * 在限定时间内取回异步结果，并把包装异常还原成原来的运行时异常；等待被中断时重新置上中断标记。
     *
     * @param stage 待等待的异步阶段
     * @return 阶段成功时的结果
     */
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

    /**
     * 记下评估引擎发出的对话请求，并按测试预先放好的回复或失败结束。
     */
    private static final class RecordingChatModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();
        private ChatResponse response;
        private RuntimeException failure;

        /**
         * 保存这次对话请求。预先放了失败时让阶段失败，否则返回预先放好的回复。
         *
         * @param request 引擎发出的对话请求
         * @return 预设失败或预设回复
         */
        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            requests.add(request);
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(response);
        }

        /**
         * 评估用例不使用嵌入，调用时直接失败。
         *
         * @param text 待嵌入的文本
         * @param executionId 所属执行
         * @return 必定失败的阶段
         */
        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("embed"));
        }
    }
}
