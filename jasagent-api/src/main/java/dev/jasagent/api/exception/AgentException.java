package dev.jasagent.api.exception;

/**
 * 本系统对外抛出的运行时异常。
 * 它带上错误分类和所属执行，调用方据此决定状态和错误体。
 */
public final class AgentException extends RuntimeException {
    private final ErrorCode code;
    private final String executionId;

    /**
     * 用错误分类和说明构造异常。
     * 执行标识和底层原因都空着，适合当时还没有执行标识的失败。
     *
     * @param code 错误分类
     * @param message 异常消息
     */
    public AgentException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    /**
     * 用错误分类、说明、执行标识和原因构造异常。
     * 执行标识随异常传给错误体和追踪，原因保留原来的失败。
     *
     * @param code 错误分类
     * @param message 异常消息
     * @param executionId 所属执行，没有时为空
     * @param cause 底层原因，没有时为空
     */
    public AgentException(ErrorCode code, String message, String executionId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.executionId = executionId;
    }

    /**
     * 返回构造时给定的错误分类。
     *
     * @return 错误分类
     */
    public ErrorCode code() {
        return code;
    }

    /**
     * 返回构造时给定的执行标识。
     * 只用说明构造时，这里为空。
     *
     * @return 执行标识，可能为空
     */
    public String executionId() {
        return executionId;
    }
}
