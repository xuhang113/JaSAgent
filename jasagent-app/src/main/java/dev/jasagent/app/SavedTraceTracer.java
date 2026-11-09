package dev.jasagent.app;

import dev.jasagent.api.otel.AgentSpan;
import dev.jasagent.api.otel.AgentTracer;
import dev.jasagent.api.otel.SpanName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存里的追踪器。根 span 使用预先分配或当场生成的 trace，子 span 只记住自己沿用的 trace，不把事件写到外部采集器。
 */
final class SavedTraceTracer implements AgentTracer {
    private final Map<String, String> traceIds = new ConcurrentHashMap<>();
    private final Map<String, String> rootSpans = new ConcurrentHashMap<>();
    private final List<String> childTraceIds = new CopyOnWriteArrayList<>();

    /**
     * 为一次执行记下已有的 trace 和根 span，之后 startRoot 会沿用它们而不是再生成。
     * 副作用：覆盖该执行先前记下的 trace 和根 span。
     *
     * @param executionId 执行编号
     * @param traceId 32 位十六进制 trace
     * @param rootSpanId 根 span 编号
     */
    void assign(String executionId, String traceId, String rootSpanId) {
        traceIds.put(executionId, traceId);
        rootSpans.put(executionId, rootSpanId);
    }

    /**
     * 返回子 span 曾经使用过的 trace 副本，供恢复测试核对 trace 没有换号。
     *
     * @return 不可变列表，元素按 startChild 的调用顺序排列
     */
    List<String> childTraceIds() {
        return List.copyOf(childTraceIds);
    }

    /**
     * 开始执行的根 span。该执行还没有 trace 或根 span 时各生成一段十六进制编号。
     *
     * @param executionId 执行编号，也写入 span
     * @return 已完成的根 span，名称是执行 span
     */
    @Override
    public CompletionStage<AgentSpan> startRoot(String executionId) {
        String traceId = traceIds.computeIfAbsent(executionId, ignored -> hex(32));
        String spanId = rootSpans.computeIfAbsent(executionId, ignored -> hex(16));
        return CompletableFuture.completedFuture(new FixedSpan(SpanName.EXECUTION, traceId, spanId, executionId));
    }

    /**
     * 开始子 span，并把它实际使用的 trace 记下来。传入的 trace 为空时记空串并照样生成新的 span 编号。父 span 编号不写入结果。
     *
     * @param name span 名称
     * @param executionId 所属执行
     * @param traceId 应沿用的 trace，可以是 null
     * @param parentSpanId 父 span 编号，当前实现不保存
     * @return 已完成的子 span
     */
    @Override
    public CompletionStage<AgentSpan> startChild(String name, String executionId, String traceId, String parentSpanId) {
        childTraceIds.add(traceId == null ? "" : traceId);
        return CompletableFuture.completedFuture(new FixedSpan(name, traceId == null ? "" : traceId, hex(16), executionId));
    }

    /**
     * 结束 span 的占位实现，不记录耗时也不外发。
     *
     * @param span 要结束的 span，当前被忽略
     * @return 已完成的空阶段
     */
    @Override
    public CompletionStage<Void> end(AgentSpan span) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 用两个随机 UUID 去掉横线后截出指定长度，作为 trace 或 span 编号。
     *
     * @param length 需要的十六进制字符数
     * @return 不含横线的十六进制串
     */
    private static String hex(int length) {
        String raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return raw.substring(0, length);
    }

    /**
     * 已经确定名称和编号的 span，创建后不再改变。
     *
     * @param name span 名称
     * @param traceId 所属 trace，子 span 可能是空串
     * @param spanId 本 span 编号
     * @param executionId 所属执行编号
     */
    private record FixedSpan(String name, String traceId, String spanId, String executionId) implements AgentSpan {
    }
}
