package dev.jasagent.core;

public interface ExecutionLog {
    void accepted(String executionId, String userId, String sessionId, String workspace);

    String status(String executionId);
}
