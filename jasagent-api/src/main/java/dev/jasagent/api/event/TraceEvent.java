package dev.jasagent.api.event;

import java.time.Instant;
import java.util.Map;

/**
 * 写进某次执行可读日志的一条事件。
 * 类型区分受理、工具、中止、恢复、评估和终态等过程，附加字段带上当时的状态等信息。
 *
 * @param executionId 所属执行
 * @param type 事件类型
 * @param message 写入日志的正文
 * @param at 事件发生的时刻
 * @param fields 附加字段，例如当时的执行状态
 */
public record TraceEvent(String executionId, String type, String message, Instant at, Map<String, String> fields) {
}
