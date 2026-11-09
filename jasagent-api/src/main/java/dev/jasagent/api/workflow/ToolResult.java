package dev.jasagent.api.workflow;

/**
 * 一次工具调用交回给调用方的文本，以及这次算不算成功。
 * 远端把调用标成错误时，文本仍然留下，成功标记为假，并且不因此抛异常。
 *
 * @param text 工具输出的文本
 * @param success 这次调用是否按成功处理
 */
public record ToolResult(String text, boolean success) {
}
