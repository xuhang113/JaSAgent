package dev.jasagent.core;

import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.llm.ChatMessage;

import java.util.List;

/**
 * 准备上下文所需的执行、角色、规则、技能、任务板、订正、历史轮次和令牌预算。
 *
 * @param executionId 这次执行的标识，压缩请求也会带上它
 * @param role 写进系统提示的角色
 * @param rules 写进系统提示的规则
 * @param skills 可放进系统提示的技能
 * @param taskBoard 尚未完成的任务说明
 * @param notes 必须保留到当前用户之前的订正
 * @param turns 已经发生的对话轮次
 * @param currentUser 这一轮要回答的用户文本
 * @param budgetTokens 整段上下文允许占用的令牌上限
 */
public record ContextInput(
        String executionId,
        String role,
        String rules,
        List<SkillText> skills,
        String taskBoard,
        List<CorrectionNote> notes,
        List<ChatMessage> turns,
        String currentUser,
        int budgetTokens) {
    /**
     * 把空的技能、订正和轮次收成不可变空列表，并把空文本收成空串。
     *
     * @param executionId 执行标识
     * @param role 角色说明；空则换成空串
     * @param rules 规则说明；空则换成空串
     * @param skills 技能列表；空则换成空列表
     * @param taskBoard 未完成任务；空则换成空串
     * @param notes 订正列表；空则换成空列表
     * @param turns 历史轮次；空则换成空列表
     * @param currentUser 当前用户文本；空则换成空串
     * @param budgetTokens 令牌预算
     */
    public ContextInput {
        skills = skills == null ? List.of() : List.copyOf(skills);
        notes = notes == null ? List.of() : List.copyOf(notes);
        turns = turns == null ? List.of() : List.copyOf(turns);
        role = role == null ? "" : role;
        rules = rules == null ? "" : rules;
        taskBoard = taskBoard == null ? "" : taskBoard;
        currentUser = currentUser == null ? "" : currentUser;
    }
}
