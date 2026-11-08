package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;

public interface TaskFailureSink {
    void markFailed(String executionId, String taskId, AgentException error);
}
