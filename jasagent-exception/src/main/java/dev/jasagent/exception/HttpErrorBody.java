package dev.jasagent.exception;

/**
 * 返回给调用方的错误正文，只保留执行标识和错误码，不携带堆栈。
 *
 * @param executionId 能定位到的执行标识；不是本系统异常时为空
 * @param code 错误码名称；无法识别时用内部错误的名称
 */
public record HttpErrorBody(String executionId, String code) {
}
