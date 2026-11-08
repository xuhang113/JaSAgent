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

class ContextCompressGateTest {
    private static final Instant CREATED = Instant.parse("2026-11-08T00:00:00Z");
    private static final String FULL = "FULL-SUMMARY-TEXT";

    @Test
    void defaultCompressAtIs085() {
        assertEquals(0.85, ContextWindow.DEFAULT_COMPRESS_AT, 0.0);
    }

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

    private static int tokens(String content) {
        String text = content == null ? "" : content;
        return Math.max(1, text.length() / 4);
    }

    private static ChatResponse reply(String summary) {
        return new ChatResponse(
                "deepseek",
                "deepseek-flash",
                ChatMessage.text("assistant", summary),
                new TokenUsage(1, 1),
                1,
                false);
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

    private static final class Scripted implements ChatModel {
        private final String summary;
        private final List<ChatRequest> requests = new ArrayList<>();

        private Scripted(String summary) {
            this.summary = summary;
        }

        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(reply(summary));
        }

        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }

    private static final class Holding implements ChatModel {
        private final CompletableFuture<ChatResponse> gate = new CompletableFuture<>();
        private int calls;
        private LlmTask task;

        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            calls += 1;
            task = request.task();
            return gate;
        }

        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }
}
