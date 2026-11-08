package dev.jasagent.api.exception;

public enum ErrorCode {
    LLM,
    TOOL,
    PLUGIN,
    MCP,
    RAG,
    WORKFLOW,
    PERSIST,
    AUTH,
    INTERRUPTED,
    CANCELLED,
    INTERNAL,
    WORKFLOW_MAX_STEPS
}
