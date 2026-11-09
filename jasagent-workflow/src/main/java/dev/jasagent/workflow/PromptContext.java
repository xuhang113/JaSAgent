package dev.jasagent.workflow;

import java.util.List;

/**
 * 组装系统提示和历史消息时要用的角色、规则、技能、未完成任务、摘要、近几轮和纠正。
 *
 * @param role 系统提示里的角色说明
 * @param rules 要写进系统提示的规则
 * @param skills 要写进系统提示的技能
 * @param unfinishedTasks 尚未完成的任务
 * @param summary 更早对话的摘要
 * @param recentTurns 紧挨当前问题的近几轮
 * @param corrections 要放在当前问题前面的纠正
 */
public record PromptContext(
        String role,
        List<String> rules,
        List<String> skills,
        List<String> unfinishedTasks,
        String summary,
        List<Turn> recentTurns,
        List<CorrectionNote> corrections) {

    /**
     * 近几轮里的一条对话。
     *
     * @param role 这一轮的角色
     * @param content 这一轮的正文
     */
    public record Turn(String role, String content) {
    }

    /**
     * 一条要放进提示的纠正。
     *
     * @param noteId 纠正笔记编号
     * @param source 纠正来源
     * @param text 纠正正文
     */
    public record CorrectionNote(String noteId, String source, String text) {
    }
}
