package dev.jasagent.core;

import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.context.CorrectionSource;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对压缩线、未达线不调用模型，以及摘要在模型返回前不会替换已发布上下文。
 */
class ContextCompressGateTest {
    /**
     * 样本订正使用的固定创建时间。
     */
    private static final Instant CREATED = Instant.parse("2026-11-08T00:00:00Z");
    /**
     * 模型成功时返回、并应发布出去的完整摘要文本。
     */
    private static final String FULL = "FULL-SUMMARY-TEXT";

    /**
     * 缺省压缩线必须是预算的 85%。断言失败则本测试失败。
     */
    @Test
    void defaultCompressAtIs085() {
        assertEquals(0.85, ContextWindow.DEFAULT_COMPRESS_AT, 0.0);
    }

    /**
     * 占用高于七成但低于压缩线时直接准备，不调用模型，摘要为空。断言失败则本测试失败。
     */
    @Test
    void belowThresholdDoesNotCallModel() {
        Scripted model = new Scripted(FULL);
        ContextInput input = around(0.80);
        double occupancy = estimate(input) / (double) input.budgetTokens();
        assertTrue(occupancy < ContextWindow.DEFAULT_COMPRESS_AT, String.valueOf(occupancy));
        assertTrue(occupancy > 0.70, String.valueOf(occupancy));
        ContextPrepared prepared = join(new ContextWindow(model).prepare(input));
        assertEquals(0, model.requests.size());
        assertEquals("", prepared.summary());
    }

    /**
     * 占用达到压缩线且不超过预算时只发一次压缩任务，并发布模型给出的摘要。断言失败则本测试失败。
     */
    @Test
    void atThresholdCallsCompressOnce() {
        Scripted model = new Scripted(FULL);
        ContextInput input = around(0.92);
        int estimated = estimate(input);
        assertTrue(estimated <= input.budgetTokens(), estimated + " vs " + input.budgetTokens());
        assertTrue(estimated >= input.budgetTokens() * ContextWindow.DEFAULT_COMPRESS_AT);
        ContextPrepared prepared = join(new ContextWindow(model).prepare(input));
        assertEquals(1, model.requests.size());
        assertEquals(LlmTask.COMPRESS, model.requests.get(0).task());
        assertEquals("compress", model.requests.get(0).task().wireName());
        assertEquals(FULL, prepared.summary());
    }

    /**
     * 压缩请求尚未完成时，已发布摘要保持为空且标记为正在压缩；模型返回后才发布新摘要并清掉标记。断言失败则本测试失败。
     */
    @Test
    void publishedSummaryStaysUntilComplete() {
        Holding holding = new Holding();
        ContextWindow window = new ContextWindow(holding);
        ContextInput light = around(0.80);
        ContextPrepared first = join(window.prepare(light));
        assertEquals("", first.summary());
        assertEquals("", window.published().summary());
        assertFalse(window.compressing());
        assertEquals(0, holding.calls);

        ContextInput heavy = around(0.92);
        CompletionStage<ContextPrepared> pending = window.prepare(heavy);
        assertEquals(1, holding.calls);
        assertEquals(LlmTask.COMPRESS, holding.task);
        assertTrue(window.compressing());
        assertFalse(pending.toCompletableFuture().isDone());
        assertEquals("", window.published().summary());

        holding.gate.complete(reply(FULL));
        ContextPrepared done = join(pending);
        assertEquals(FULL, done.summary());
        assertEquals(FULL, window.published().summary());
        assertFalse(window.compressing());
    }

    /**
     * 第二次压缩失败时保留第一次已发布的摘要，不把失败回复写进消息，也不再显示为正在压缩。断言失败则本测试失败。
     */
    @Test
    void failedCompressKeepsPublishedSummary() {
        OnceThenFail model = new OnceThenFail();
        ContextWindow window = new ContextWindow(model);
        ContextPrepared first = join(window.prepare(around(0.92)));
        assertEquals(FULL, first.summary());
        assertEquals(FULL, window.published().summary());
        AgentException error = org.junit.jupiter.api.Assertions.assertThrows(AgentException.class, () -> join(window.prepare(around(0.92))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(FULL, window.published().summary());
        assertFalse(window.compressing());
        assertFalse(window.published().messages().stream().anyMatch(message -> message.content() != null && message.content().contains("PARTIAL")));
    }

    /**
     * 构造一份估算占用接近目标比例、且不超过预算的上下文。
     *
     * @param target 期望的预算占用比例
     * @return 最接近该比例的样本；差距过大时测试失败
     */
    private static ContextInput around(double target) {
        int budget = 1000;
        ContextInput best = null;
        double bestGap = Double.MAX_VALUE;
        for (int chars = 0; chars <= 6000; chars += 4) {
            ContextInput input = sample(budget, chars);
            double occupancy = estimate(input) / (double) budget;
            if (occupancy > 1.0) {
                break;
            }
            double gap = Math.abs(occupancy - target);
            if (gap < bestGap) {
                bestGap = gap;
                best = input;
            }
        }
        if (best == null || bestGap > 0.03) {
            throw new AssertionError("占用没有落到 " + target + "，最近差距 " + bestGap);
        }
        return best;
    }

    /**
     * 用给定预算和一段可变旧对话拼出样本，并带上固定的最近四条、技能和订正。
     *
     * @param budget 令牌预算
     * @param oldChars 最早那条用户消息的字符数
     * @return 供占用估算和压缩使用的输入
     */
    private static ContextInput sample(int budget, int oldChars) {
        List<ChatMessage> turns = new ArrayList<>();
        turns.add(ChatMessage.text("user", "O".repeat(oldChars)));
        turns.add(ChatMessage.text("user", "1"));
        turns.add(ChatMessage.text("assistant", "2"));
        turns.add(ChatMessage.text("user", "3"));
        turns.add(ChatMessage.text("assistant", "4"));
        return new ContextInput(
                "exec-ctx",
                "助手",
                "R",
                List.of(new SkillText("s", "d", "")),
                "B",
                List.of(new CorrectionNote("n1", "sess-1", "exec-ctx", CREATED, CorrectionSource.USER, "N")),
                turns,
                "U",
                budget);
    }

    /**
     * 按与产品相同的字符折算规则，估算这份输入渲染后的令牌。
     *
     * @param input 待估算的上下文
     * @return 系统提示、轮次、订正和当前用户的令牌合计
     */
    private static int estimate(ContextInput input) {
        int tokens = tokens(system(input));
        for (ChatMessage turn : input.turns()) {
            if (turn != null) {
                tokens += tokens(turn.content());
            }
        }
        for (CorrectionNote note : input.notes()) {
            tokens += tokens(note.text() == null ? "" : note.text());
        }
        tokens += tokens(input.currentUser());
        return tokens;
    }

    /**
     * 按产品的系统提示格式拼出角色、规则、任务板、执行标识和技能。
     *
     * @param input 提示素材
     * @return 系统提示全文
     */
    private static String system(ContextInput input) {
        StringBuilder builder = new StringBuilder();
        builder.append("角色：").append(input.role()).append('\n');
        builder.append("规则：").append(input.rules()).append('\n');
        builder.append("未完成任务：").append(input.taskBoard()).append('\n');
        builder.append("executionId：").append(input.executionId()).append('\n');
        for (SkillText skill : input.skills()) {
            if (skill == null) {
                continue;
            }
            builder.append("技能：");
            builder.append(skill.name() == null ? "" : skill.name());
            builder.append(' ');
            builder.append(skill.description() == null ? "" : skill.description());
            if (skill.body() != null && !skill.body().isBlank()) {
                builder.append(' ').append(skill.body());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 把一段文本按每四个字符至少一个令牌折算。
     *
     * @param content 文本；空也算一个令牌
     * @return 折算后的令牌数
     */
    private static int tokens(String content) {
        String text = content == null ? "" : content;
        return Math.max(1, text.length() / 4);
    }

    /**
     * 包一条只含摘要正文的模型回复。
     *
     * @param summary 摘要正文
     * @return 带一单位用量的回复
     */
    private static ChatResponse reply(String summary) {
        return new ChatResponse(
                "deepseek",
                "deepseek-flash",
                ChatMessage.text("assistant", summary),
                new TokenUsage(1, 1),
                1,
                false);
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
            throw new IllegalStateException(cause);
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 记下每次对话请求，并立刻用固定摘要回复。
     */
    private static final class Scripted implements ChatModel {
        private final String summary;
        private final List<ChatRequest> requests = new ArrayList<>();

        /**
         * 指定以后每次回复使用的摘要。
         *
         * @param summary 立即返回的摘要正文
         */
        private Scripted(String summary) {
            this.summary = summary;
        }

        /**
         * 保存请求并立即返回固定摘要。
         *
         * @param request 上下文窗口发出的压缩请求
         * @return 已完成的固定摘要回复
         */
        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(reply(summary));
        }

        /**
         * 压缩测试不使用嵌入，调用时直接失败。
         *
         * @param text 待嵌入文本
         * @param executionId 所属执行
         * @return 必定失败的阶段
         */
        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }

    /**
     * 把第一次对话挂起，直到测试放行，用来观察发布前的状态。
     */
    private static final class Holding implements ChatModel {
        private final CompletableFuture<ChatResponse> gate = new CompletableFuture<>();
        private int calls;
        private LlmTask task;

        /**
         * 记下调用次数和任务类型，并返回尚未完成的门。副作用是增加调用计数。
         *
         * @param request 上下文窗口发出的请求
         * @return 要等测试放行才完成的回复
         */
        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            calls += 1;
            task = request.task();
            return gate;
        }

        /**
         * 压缩测试不使用嵌入，调用时直接失败。
         *
         * @param text 待嵌入文本
         * @param executionId 所属执行
         * @return 必定失败的阶段
         */
        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }

    /**
     * 第一次对话返回完整摘要，之后的对话以模型错误失败。
     */
    private static final class OnceThenFail implements ChatModel {
        private int calls;

        /**
         * 第一次返回完整摘要，其后返回失败。副作用是增加调用计数。
         *
         * @param request 上下文窗口发出的请求
         * @return 第一次成功，之后失败的阶段
         */
        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            calls += 1;
            if (calls == 1) {
                return CompletableFuture.completedFuture(reply(FULL));
            }
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "PARTIAL 模型未配置"));
        }

        /**
         * 压缩测试不使用嵌入，调用时直接失败。
         *
         * @param text 待嵌入文本
         * @param executionId 所属执行
         * @return 必定失败的阶段
         */
        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }
}
