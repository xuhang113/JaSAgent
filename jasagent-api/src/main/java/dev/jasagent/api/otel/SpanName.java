package dev.jasagent.api.otel;

/**
 * 本系统在 OpenTelemetry 里使用的 span 名称和执行属性名。
 * 同一次执行的根 span 和子 span 都带上执行标识属性，并共用一个 trace。
 */
public final class SpanName {
    /**
     * 一次执行的根 span 名称。
     */
    public static final String EXECUTION = "jasagent.execution";
    /**
     * 主从调度这一步使用的子 span 名称。
     */
    public static final String AGENT_SCHEDULE = "agent.schedule";
    /**
     * 工作流节点使用的子 span 名称。
     */
    public static final String WORKFLOW_NODE = "workflow.node";
    /**
     * 模型对话使用的子 span 名称。
     */
    public static final String LLM_CHAT = "llm.chat";
    /**
     * 工具调用使用的子 span 名称。
     */
    public static final String TOOL_CALL = "tool.call";
    /**
     * 插件调用使用的子 span 名称。
     */
    public static final String PLUGIN_INVOKE = "plugin.invoke";
    /**
     * MCP 远端调用使用的子 span 名称。
     */
    public static final String MCP_CALL = "mcp.call";
    /**
     * 检索召回使用的子 span 名称。
     */
    public static final String RAG_RETRIEVE = "rag.retrieve";
    /**
     * 检索重排使用的子 span 名称。
     */
    public static final String RAG_RERANK = "rag.rerank";
    /**
     * 评估运行使用的子 span 名称。
     */
    public static final String EVAL_RUN = "eval.run";
    /**
     * 写在每个 span 上的执行标识属性名。
     */
    public static final String EXECUTION_ID = "jasagent.execution_id";

    /**
     * 禁止从外面构造。
     * 这些名称是固定字面量，没有需要保存的状态。
     */
    private SpanName() {
    }
}
