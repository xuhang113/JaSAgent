package dev.jasagent.core;

import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.workflow.CorrectionNote;

import java.util.ArrayList;
import java.util.List;

public final class ContextWindow {
    private final ChatModel model;

    public ContextWindow(ChatModel model) {
        this.model = model;
    }

    public ContextPrepared prepare(ContextInput input) {
        List<ChatMessage> turns = new ArrayList<>(input.turns());
        List<SkillText> skills = new ArrayList<>(input.skills());
        String summary = "";
        if (estimate(render(input, turns, skills, summary)) > input.budgetTokens()) {
            int keep = Math.min(4, turns.size());
            List<ChatMessage> older = new ArrayList<>();
            List<ChatMessage> keptTools = new ArrayList<>();
            for (int index = 0; index < turns.size() - keep; index++) {
                ChatMessage message = turns.get(index);
                if ("tool".equals(message.role())) {
                    keptTools.add(message);
                } else {
                    older.add(message);
                }
            }
            List<ChatMessage> recent = new ArrayList<>(turns.subList(turns.size() - keep, turns.size()));
            summary = model.complete(new ChatRequest(LlmTask.COMPRESS, older, List.of(), null, input.executionId()))
                    .toCompletableFuture()
                    .join()
                    .message()
                    .content();
            turns = new ArrayList<>();
            turns.add(ChatMessage.text("system", summary));
            turns.addAll(keptTools);
            turns.addAll(recent);
        }
        if (estimate(render(input, turns, skills, summary)) > input.budgetTokens()) {
            int boundary = Math.max(0, turns.size() - 4);
            for (int index = 0; index < boundary; index++) {
                if ("tool".equals(turns.get(index).role())) {
                    turns.set(index, ChatMessage.text("tool", "tool: [collapsed]"));
                }
            }
        }
        if (estimate(render(input, turns, skills, summary)) > input.budgetTokens()) {
            List<SkillText> shrunk = new ArrayList<>();
            for (SkillText skill : skills) {
                shrunk.add(new SkillText(skill.name(), skill.description(), ""));
            }
            skills = shrunk;
        }
        return new ContextPrepared(render(input, turns, skills, summary));
    }

    private static List<ChatMessage> render(ContextInput input, List<ChatMessage> turns, List<SkillText> skills, String summary) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.text("system", system(input, skills)));
        if (summary != null && !summary.isBlank() && turns.stream().noneMatch(message -> summary.equals(message.content()))) {
            messages.add(ChatMessage.text("system", summary));
        }
        messages.addAll(turns);
        for (CorrectionNote note : input.notes()) {
            messages.add(ChatMessage.text("system", "纠正：" + note.text()));
        }
        messages.add(ChatMessage.text("user", input.currentUser()));
        return messages;
    }

    private static String system(ContextInput input, List<SkillText> skills) {
        StringBuilder builder = new StringBuilder();
        builder.append("规则：").append(input.rules()).append('\n');
        builder.append("未完成任务：").append(input.taskBoard()).append('\n');
        builder.append("executionId：").append(input.executionId()).append('\n');
        for (SkillText skill : skills) {
            builder.append("技能：").append(skill.name()).append(' ').append(skill.description());
            if (skill.body() != null && !skill.body().isBlank()) {
                builder.append(' ').append(skill.body());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static int estimate(List<ChatMessage> messages) {
        int tokens = 0;
        for (ChatMessage message : messages) {
            tokens += Math.max(1, message.content().length() / 4);
        }
        return tokens;
    }
}
