package dev.jasagent.otel;

import java.util.Map;

/**
 * 从已结束的跨度里摘出的只读视图，便于测试核对名称、链路和属性。
 *
 * @param name 跨度名称
 * @param traceId 所属链路标识
 * @param spanId 这个跨度自己的标识
 * @param parentSpanId 父跨度标识；根跨度没有有效父级时仍保留读取到的值
 * @param attributes 跨度上的字符串化属性
 */
public record ObservedSpan(
        String name,
        String traceId,
        String spanId,
        String parentSpanId,
        Map<String, String> attributes) {
}
