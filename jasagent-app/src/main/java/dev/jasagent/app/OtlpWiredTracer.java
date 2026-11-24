package dev.jasagent.app;

import dev.jasagent.api.otel.AgentSpan;
import dev.jasagent.api.otel.AgentTracer;
import dev.jasagent.otel.ExecutionTelemetry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把内存追踪和可选的 OTLP 导出接在同一条 {@link AgentTracer} 上。
 * 未配置端点时只使用 {@link SavedTraceTracer}，不创建导出器。
 * 配置了非空端点时，开 span 会同时调用已有的 {@link ExecutionTelemetry}，由它按原约定做 OTLP HTTP 导出。
 * 这里不把配置里的口令、令牌或请求头写进 span。导出器只收到端点地址和执行编号。
 */
final class OtlpWiredTracer implements AgentTracer, AutoCloseable {
    /**
     * 导出地址的配置名。空白表示不外发。
     */
    static final String ENDPOINT = "jasagent.otel.endpoint";

    private final SavedTraceTracer memory;
    private final ExecutionTelemetry telemetry;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 保存内存追踪器和可选的导出器。导出器为空表示这次不外发。
     *
     * @param memory 现有的内存追踪器，负责 trace 编号和恢复时沿用的 span
     * @param telemetry 配置了端点时的 OTLP 导出；未配置时为空
     */
    private OtlpWiredTracer(SavedTraceTracer memory, ExecutionTelemetry telemetry) {
        this.memory = memory;
        this.telemetry = telemetry;
    }

    /**
     * 按配置打开追踪。端点缺失或只有空白时不创建导出器，内存行为与原来的 {@link SavedTraceTracer} 相同。
     * 端点非空时只把这一项交给 {@link ExecutionTelemetry}，不把其余配置传进去。
     *
     * @param configuration 运行配置，可以是 null
     * @return 接到 {@link AgentTracer} 上的追踪器
     */
    static OtlpWiredTracer open(Map<String, String> configuration) {
        String endpoint = endpoint(configuration);
        ExecutionTelemetry telemetry = null;
        if (endpoint != null) {
            telemetry = ExecutionTelemetry.open(Map.of(ENDPOINT, endpoint));
        }
        return new OtlpWiredTracer(new SavedTraceTracer(), telemetry);
    }

    /**
     * 为一次执行记下已有的 trace 和根 span。只写内存追踪器，不外发。
     *
     * @param executionId 执行编号
     * @param traceId 32 位十六进制 trace
     * @param rootSpanId 根 span 编号
     */
    void assign(String executionId, String traceId, String rootSpanId) {
        memory.assign(executionId, traceId, rootSpanId);
    }

    /**
     * 返回子 span 曾经使用过的 trace，供恢复测试核对没有换号。
     *
     * @return 不可变列表
     */
    List<String> childTraceIds() {
        return memory.childTraceIds();
    }

    /**
     * 这次是否打开了 OTLP 导出。空白端点时为假。
     *
     * @return 配置了非空端点时为真
     */
    boolean exporting() {
        return telemetry != null && telemetry.otlpExportEnabled();
    }

    /**
     * 开始根 span。先沿用内存追踪器的编号，再在配置了端点时让已有导出器记下名为 {@code jasagent.execution} 的根 span。
     * 不把调用方的口令、令牌或请求头写进 span。
     *
     * @param executionId 执行编号
     * @return 内存追踪器给出的根 span
     */
    @Override
    public CompletionStage<AgentSpan> startRoot(String executionId) {
        CompletionStage<AgentSpan> span = memory.startRoot(executionId);
        if (telemetry != null) {
            telemetry.startRoot(executionId);
        }
        return span;
    }

    /**
     * 开始子 span。内存追踪器仍记下实际使用的 trace；配置了端点时，同一执行的导出器再记一条同名子 span。
     *
     * @param name span 名称
     * @param executionId 所属执行
     * @param traceId 应沿用的 trace
     * @param parentSpanId 父 span 编号
     * @return 内存追踪器给出的子 span
     */
    @Override
    public CompletionStage<AgentSpan> startChild(String name, String executionId, String traceId, String parentSpanId) {
        CompletionStage<AgentSpan> span = memory.startChild(name, executionId, traceId, parentSpanId);
        if (telemetry != null) {
            telemetry.startChild(executionId, name);
        }
        return span;
    }

    /**
     * 结束内存里的 span。导出器在打开 span 时已经按原约定结束并排队，这里不再写属性。
     *
     * @param span 要结束的 span
     * @return 内存追踪器的结束阶段
     */
    @Override
    public CompletionStage<Void> end(AgentSpan span) {
        return memory.end(span);
    }

    /**
     * 关闭导出器，把已经结束的 span 刷到端点。没有导出器时什么也不做。可以重复调用。
     */
    @Override
    public void close() {
        if (telemetry != null && closed.compareAndSet(false, true)) {
            telemetry.close();
        }
    }

    /**
     * 读取端点。缺失或只有空白时返回 null，表示不导出。
     *
     * @param configuration 运行配置，可以是 null
     * @return 去掉首尾空白的端点；不导出时为空
     */
    private static String endpoint(Map<String, String> configuration) {
        if (configuration == null) {
            return null;
        }
        String value = configuration.get(ENDPOINT);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
