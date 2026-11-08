package dev.jasagent.api.exception;

public enum ErrorCode {
    LLM,
    TOOL,
    PLUGIN,
    MCP,
    RAG,
    WORKFLOW,
    WORKFLOW_MAX_STEPS,
    PERSIST,
    AUTH,
    INTERRUPTED,
    CANCELLED,
    INTERNAL
}
