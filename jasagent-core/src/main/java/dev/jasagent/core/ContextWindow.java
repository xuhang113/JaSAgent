package dev.jasagent.core;

import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.LlmTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ContextWindow {
    public static final int DEFAULT_BUDGET_TOKENS = 8000;
    public static final double DEFAULT_COMPRESS_AT = 0.85;
    public static final String COMPRESS_AT = "jasagent.context.compress-at";
    private static final int RECENT_TURNS = 4;
    private static final String COLLAPSED_TOOL = "tool: [collapsed]";

    private final ChatModel model;
    private final double compressAt;
    private final Object publishLock = new Object();
    private ContextPrepared published;
    private boolean compressing;

    public ContextWindow(ChatModel model) {
        this(model, DEFAULT_COMPRESS_AT);
    }

    public ContextWindow(ChatModel model, double compressAt) {
        if (model == null) {
            throw new AgentException(ErrorCode.INTERNAL, "ChatModel");
        }
        if (Double.isNaN(compressAt) || compressAt <= 0.0 || compressAt > 1.0) {
            throw new AgentException(ErrorCode.INTERNAL, COMPRESS_AT);
        }
        this.model = model;
        this.compressAt = compressAt;
    }

    public static double compressAt(Map<String, String> configuration) {
        if (configuration == null || !configuration.containsKey(COMPRESS_AT)) {
            return DEFAULT_COMPRESS_AT;
        }
        String raw = configuration.get(COMPRESS_AT);
        if (raw == null || raw.isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, COMPRESS_AT);
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (Double.isNaN(parsed) || parsed <= 0.0 || parsed > 1.0) {
                throw new AgentException(ErrorCode.INTERNAL, COMPRESS_AT);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new AgentException(ErrorCode.INTERNAL, COMPRESS_AT, null, ex);
        }
    }

    public ContextPrepared published() {
        synchronized (publishLock) {
            return published;
        }
    }

    public boolean compressing() {
        synchronized (publishLock) {
            return compressing;
        }
    }

    public CompletionStage<ContextPrepared> prepare(ContextInput input) {
        if (input == null || input.executionId() == null || input.executionId().isBlank()) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "executionId"));
        }
        if (input.budgetTokens() < 1) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "budgetTokens"));
        }
        List<ChatMessage> turns = new ArrayList<>(input.turns());
        List<SkillText> skills = new ArrayList<>(input.skills());
        int estimated = estimate(render(input, turns, skills, ""));
        if (!reached(estimated, input.budgetTokens())) {
            ContextPrepared ready = new ContextPrepared(render(input, turns, skills, ""), "");
            publish(ready);
            return CompletableFuture.completedFuture(ready);
        }
        int keep = Math.min(RECENT_TURNS, turns.size());
        List<ChatMessage> older = new ArrayList<>();
        List<ChatMessage> keptTools = new ArrayList<>();
        for (int index = 0; index < turns.size() - keep; index++) {
            ChatMessage message = turns.get(index);
            if (message != null && "tool".equals(message.role())) {
                keptTools.add(message);
            } else if (message != null) {
                older.add(message);
            }
        }
        List<ChatMessage> recent = new ArrayList<>(turns.subList(turns.size() - keep, turns.size()));
        if (older.isEmpty()) {
            ContextPrepared reduced = reduce(input, keptTools, recent, skills, "", input.budgetTokens());
            publish(reduced);
            return CompletableFuture.completedFuture(reduced);
        }
        ChatRequest request = new ChatRequest(LlmTask.COMPRESS, List.copyOf(older), List.of(), null, input.executionId());
        markCompressing();
        CompletionStage<ChatResponse> stage = model.complete(request);
        if (stage == null) {
            clearCompressing();
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "compress", input.executionId(), null));
        }
        return stage.thenApply(response -> {
            ContextPrepared done = reduce(
                    input,
                    keptTools,
                    recent,
                    new ArrayList<>(input.skills()),
                    summaryText(response),
                    input.budgetTokens());
            publish(done);
            return done;
        }).whenComplete((done, error) -> {
            if (error != null) {
                clearCompressing();
            }
        });
    }

    private boolean reached(int estimated, int budget) {
        return estimated >= budget * compressAt;
    }

    private void publish(ContextPrepared prepared) {
        synchronized (publishLock) {
            published = prepared;
            compressing = false;
        }
    }

    private void markCompressing() {
        synchronized (publishLock) {
            compressing = true;
        }
    }

    private void clearCompressing() {
        synchronized (publishLock) {
            compressing = false;
        }
    }

    private static ContextPrepared reduce(
            ContextInput input,
            List<ChatMessage> keptTools,
            List<ChatMessage> recent,
            List<SkillText> skills,
            String summary,
            int budget) {
        List<ChatMessage> conversation = new ArrayList<>(keptTools.size() + recent.size());
        conversation.addAll(keptTools);
        conversation.addAll(recent);
        if (estimate(render(input, conversation, skills, summary)) > budget) {
            for (int index = 0; index < keptTools.size(); index++) {
                if ("tool".equals(conversation.get(index).role())) {
                    conversation.set(index, ChatMessage.text("tool", COLLAPSED_TOOL));
                }
            }
        }
        if (estimate(render(input, conversation, skills, summary)) > budget) {
            List<SkillText> shrunk = new ArrayList<>();
            for (SkillText skill : skills) {
                shrunk.add(new SkillText(skill.name(), skill.description(), ""));
            }
            skills = shrunk;
        }
        String kept = summary == null ? "" : summary;
        return new ContextPrepared(render(input, conversation, skills, kept), kept);
    }

    private static List<ChatMessage> render(ContextInput input, List<ChatMessage> turns, List<SkillText> skills, String summary) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.text("system", system(input, skills)));
        if (summary != null && !summary.isBlank()) {
            messages.add(ChatMessage.text("summary", summary));
        }
        for (ChatMessage turn : turns) {
            if (turn != null) {
                messages.add(turn);
            }
        }
        for (CorrectionNote note : input.notes()) {
            messages.add(ChatMessage.text("correction", note.text() == null ? "" : note.text()));
        }
        messages.add(ChatMessage.text("user", input.currentUser()));
        return messages;
    }

    private static String system(ContextInput input, List<SkillText> skills) {
        StringBuilder builder = new StringBuilder();
        builder.append("角色：").append(input.role()).append('\n');
        builder.append("规则：").append(input.rules()).append('\n');
        builder.append("未完成任务：").append(input.taskBoard()).append('\n');
        builder.append("executionId：").append(input.executionId()).append('\n');
        for (SkillText skill : skills) {
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

    private static String summaryText(ChatResponse response) {
        if (response == null || response.message() == null || response.message().content() == null) {
            return "";
        }
        return response.message().content();
    }

    private static int estimate(List<ChatMessage> messages) {
        int tokens = 0;
        for (ChatMessage message : messages) {
            String content = message == null || message.content() == null ? "" : message.content();
            tokens += Math.max(1, content.length() / 4);
        }
        return tokens;
    }
}
