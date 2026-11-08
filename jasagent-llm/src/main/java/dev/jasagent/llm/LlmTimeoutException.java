package dev.jasagent.llm;

public final class LlmTimeoutException extends RuntimeException {
    private final String executionId;

    public LlmTimeoutException(String executionId, String message) {
        this(executionId, message, null);
    }

    public LlmTimeoutException(String executionId, String message, Throwable cause) {
        super(message, cause);
        this.executionId = executionId;
    }

    public String executionId() {
        return executionId;
    }

    public static boolean matches(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof LlmTimeoutException || current instanceof dev.langchain4j.exception.TimeoutException) {
                return true;
            }
            if ("TimeoutException".equals(current.getClass().getSimpleName())) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }
}