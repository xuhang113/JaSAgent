package dev.jasagent.api.otel;

public final class SpanName {
    public static final String EXECUTION = "jasagent.execution";
    public static final String AGENT_SCHEDULE = "agent.schedule";
    public static final String WORKFLOW_NODE = "workflow.node";
    public static final String LLM_CHAT = "llm.chat";
    public static final String TOOL_CALL = "tool.call";
    public static final String PLUGIN_INVOKE = "plugin.invoke";
    public static final String MCP_CALL = "mcp.call";
    public static final String RAG_RETRIEVE = "rag.retrieve";
    public static final String RAG_RERANK = "rag.rerank";
    public static final String EVAL_RUN = "eval.run";
    public static final String EXECUTION_ID = "jasagent.execution_id";

    private SpanName() {
    }
}
