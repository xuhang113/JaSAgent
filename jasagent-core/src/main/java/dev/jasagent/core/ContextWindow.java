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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ContextWindow {
    public static final int DEFAULT_BUDGET_TOKENS = 8000;
    private static final int RECENT_TURNS = 4;
    private static final String COLLAPSED_TOOL = "tool: [collapsed]";

    private final ChatModel model;

    public ContextWindow(ChatModel model) {
        if (model == null) {
            throw new AgentException(ErrorCode.INTERNAL, "ChatModel");
        }
        this.model = model;
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
        if (estimate(render(input, turns, skills, "")) <= input.budgetTokens()) {
            return CompletableFuture.completedFuture(new ContextPrepared(render(input, turns, skills, ""), ""));
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
            return CompletableFuture.completedFuture(reduce(input, keptTools, recent, skills, "", input.budgetTokens()));
        }
        ChatRequest request = new ChatRequest(LlmTask.COMPRESS, List.copyOf(older), List.of(), null, input.executionId());
        CompletionStage<ChatResponse> stage = model.complete(request);
        if (stage == null) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "compress", input.executionId(), null));
        }
        return stage.thenApply(response -> reduce(
                input,
                keptTools,
                recent,
                new ArrayList<>(input.skills()),
                summaryText(response),
                input.budgetTokens()));
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
