package dev.jasagent.llm;

/**
 * 表示一次模型调用在等待上限内没有返回，并带上当时的执行编号。
 */
public final class LlmTimeoutException extends RuntimeException {
    private final String executionId;

    /**
     * 记录一次没有下层原因的模型超时。
     *
     * @param executionId 这次对话或嵌入所属的执行编号
     * @param message 给调用方看的超时说明
     */
    public LlmTimeoutException(String executionId, String message) {
        this(executionId, message, null);
    }

    /**
     * 记录一次模型超时，并保留触发它的底层异常。
     *
     * @param executionId 这次对话或嵌入所属的执行编号
     * @param message 给调用方看的超时说明
     * @param cause 底层超时或其他触发原因，可以为 null
     */
    public LlmTimeoutException(String executionId, String message, Throwable cause) {
        super(message, cause);
        this.executionId = executionId;
    }

    /**
     * 返回超时时记下的执行编号，方便错误和追踪对齐同一次运行。
     *
     * @return 构造时传入的执行编号
     */
    public String executionId() {
        return executionId;
    }

    /**
     * 沿着异常链判断这是不是模型超时，包括本类型、LangChain4j 超时，以及类名就是 TimeoutException 的异常。
     *
     * @param ex 要检查的异常；null 视为不是超时
     * @return 链上出现超时异常时为 true
     */
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