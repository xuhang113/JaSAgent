package dev.jasagent.api.otel;

/**
 * 一次已经打开的追踪 span 对外露出的标识。
 * 调用方用这些标识把子 span 挂到同一次执行上，结束时再把 span 交回去。
 */
public interface AgentSpan {
    /**
     * 返回这个 span 的操作名称，例如执行根 span 或某个节点。
     *
     * @return span 名称
     */
    String name();

    /**
     * 返回这个 span 所属 trace 的标识。
     * 同一次执行打开的 span 共用它。
     *
     * @return trace 标识
     */
    String traceId();

    /**
     * 返回这个 span 自己的标识。
     * 后续子 span 用它作为父级。
     *
     * @return span 标识
     */
    String spanId();

    /**
     * 返回这个 span 记录的执行标识。
     *
     * @return 执行标识
     */
    String executionId();
}
