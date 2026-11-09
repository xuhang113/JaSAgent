package dev.jasagent.api.llm;

/**
 * AI Gateway 的可用性探针。路由在把主循环对话或文本嵌入发给网关之前先问它。
 */
public interface GatewayHealth {
    /**
     * 判断网关当前能否接受对话或嵌入。路由在选网关之前读取这个结果。
     *
     * @return 探针认为网关可用时为 true
     */
    boolean healthy();
}
