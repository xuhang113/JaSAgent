package dev.jasagent.api.context;

/**
 * 一条纠正说明的来源。
 * 组上下文时这些说明整段放在当前用户消息之前，压缩时也不会被删掉。
 */
public enum CorrectionSource {
    /**
     * 用户自己写下的纠正。
     */
    USER,
    /**
     * 助手引用的内容对不上工具结果，因而补上的纠正。
     */
    TOOL_CONTRADICTION,
    /**
     * 落地核对发现模型输出缺少依据时写下的纠正。
     */
    GROUNDING,
    /**
     * 评估结论需要写回会话上下文时使用的来源。
     */
    EVALUATION
}
