package dev.jasagent.api.exec;

/**
 * 一次执行的编号。单独包成类型，避免和会话编号、追踪编号混用。
 *
 * @param value 执行编号字符串
 */
public record ExecutionId(String value) {
}
