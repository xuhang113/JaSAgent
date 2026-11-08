package dev.jasagent.api.exception;

public final class AgentException extends RuntimeException {
    private final ErrorCode code;
    private final String executionId;

    public AgentException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public AgentException(ErrorCode code, String message, String executionId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.executionId = executionId;
    }

    public ErrorCode code() {
        return code;
    }

    public String executionId() {
        return executionId;
    }
}
