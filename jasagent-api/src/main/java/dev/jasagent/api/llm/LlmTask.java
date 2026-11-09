package dev.jasagent.api.llm;

/**
 * 一次模型调用在路由里的任务种类。线上名称写入调用记录，并决定能否走网关、失败后是否改道。
 */
public enum LlmTask {
    /**
     * 主循环推理：决定回复正文或下一步工具调用。网关可用时优先走网关，失败再回退 DeepSeek。
     * 超时且剩余步数至少为 1 时重试一次。线上名称为 react。
     */
    REACT("react"),
    /**
     * 上下文压缩：把较早的对话收成摘要。固定走 DeepSeek，失败后不改道到网关。线上名称为 compress。
     */
    COMPRESS("compress"),
    /**
     * 检索重排调用。固定走 DeepSeek。线上名称为 rerank。
     */
    RERANK("rerank"),
    /**
     * 运行评判：根据完成内容和工具轨迹给出结构化评判。固定走 DeepSeek，失败后不改道到网关。线上名称为 judge。
     */
    JUDGE("judge");

    private final String wireName;

    /**
     * 记下这个任务在调用记录和追踪里使用的线上名称。
     *
     * @param wireName 写入调用记录的任务名，例如 react
     */
    LlmTask(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 返回写入调用记录和追踪的线上名称。
     *
     * @return 线上名称，取值为 react、compress、rerank 或 judge
     */
    public String wireName() {
        return wireName;
    }
}
