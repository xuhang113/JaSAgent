package dev.jasagent.otel;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 为一次执行建立链路根跨度，并按配置决定是否把跨度批量导出到 OTLP。
 */
public final class ExecutionTelemetry implements AutoCloseable {
    /**
     * 写在每个跨度上的执行标识属性名。
     */
    static final String EXECUTION_ID = "jasagent.execution_id";
    /**
     * 一次执行的根跨度名称。
     */
    static final String ROOT_SPAN = "jasagent.execution";

    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;
    private final SpanRecorder recorder;
    private final SpanExporter exporter;
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();
    private final Map<String, String> traceIds = new ConcurrentHashMap<>();
    private final Map<String, String> rootSpanIds = new ConcurrentHashMap<>();

    /**
     * 保存已经建好的追踪器、内存记录器和可选的导出器。
     *
     * @param tracerProvider 要在关闭时一起关掉的追踪提供器
     * @param tracer 用来创建根跨度和子跨度的追踪器
     * @param recorder 收集已结束跨度的内存处理器
     * @param exporter 配置了端点时的 OTLP 导出器；未启用时为空
     */
    private ExecutionTelemetry(SdkTracerProvider tracerProvider, Tracer tracer, SpanRecorder recorder, SpanExporter exporter) {
        this.tracerProvider = tracerProvider;
        this.tracer = tracer;
        this.recorder = recorder;
        this.exporter = exporter;
    }

    /**
     * 按配置打开追踪。没有端点时只在内存里记录；有端点时再挂上 HTTP 上的 OTLP 批量导出。
     *
     * @param configuration 可含 {@code jasagent.otel.endpoint} 的配置；空则不导出
     * @return 已可开始记录执行跨度的遥测对象
     */
    public static ExecutionTelemetry open(Map<String, String> configuration) {
        Map<String, String> config = configuration == null ? Map.of() : configuration;
        String endpoint = config.get("jasagent.otel.endpoint");
        SpanRecorder recorder = new SpanRecorder();
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder().addSpanProcessor(recorder);
        SpanExporter exporter = null;
        if (endpoint != null && !endpoint.isBlank()) {
            exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }
        SdkTracerProvider provider = builder.build();
        Tracer tracer = provider.get("dev.jasagent");
        return new ExecutionTelemetry(provider, tracer, recorder, exporter);
    }

    /**
     * 为这次执行开启根跨度并立即结束，同时记住链路标识和根跨度标识。副作用是登记上下文并留下一条已结束的根跨度。
     *
     * @param executionId 这次执行的标识，会写成跨度属性
     */
    public void startRoot(String executionId) {
        Span span = tracer.spanBuilder(ROOT_SPAN)
                .setAttribute(EXECUTION_ID, executionId)
                .startSpan();
        contexts.put(executionId, Context.root().with(span));
        traceIds.put(executionId, span.getSpanContext().getTraceId());
        rootSpanIds.put(executionId, span.getSpanContext().getSpanId());
        span.end();
    }

    /**
     * 在该执行已保存的上下文下开启一个子跨度并立即结束。副作用是向内存记录追加这条子跨度。
     *
     * @param executionId 父上下文所属的执行
     * @param name 子跨度名称，例如模型或工具调用
     */
    public void startChild(String executionId, String name) {
        Context parent = contexts.get(executionId);
        Span span = tracer.spanBuilder(name)
                .setParent(parent)
                .setAttribute(EXECUTION_ID, executionId)
                .startSpan();
        span.end();
    }

    /**
     * 返回先前为该执行记下的链路标识。
     *
     * @param executionId 执行标识
     * @return 根跨度上的链路标识；尚未开始时为空
     */
    public String traceId(String executionId) {
        return traceIds.get(executionId);
    }

    /**
     * 返回先前为该执行记下的根跨度标识。
     *
     * @param executionId 执行标识
     * @return 根跨度标识；尚未开始时为空
     */
    public String rootSpanId(String executionId) {
        return rootSpanIds.get(executionId);
    }

    /**
     * 取出带有该执行标识的已结束跨度。
     *
     * @param executionId 用来过滤属性的执行标识
     * @return 按结束顺序收集到的跨度视图
     */
    public List<ObservedSpan> spans(String executionId) {
        List<ObservedSpan> found = new ArrayList<>();
        for (SpanData span : recorder.finished()) {
            String id = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(EXECUTION_ID));
            if (executionId.equals(id)) {
                found.add(observe(span));
            }
        }
        return List.copyOf(found);
    }

    /**
     * 把该执行的追踪上下文带进虚拟线程，让异步动作仍能读到同一条链路。动作里的普通异常会收成带执行标识的内部错误。
     *
     * @param executionId 要恢复的执行上下文
     * @param call 在虚拟线程中执行的动作
     * @return 动作成功时的结果阶段
     */
    public <T> CompletionStage<T> propagate(String executionId, Callable<T> call) {
        Context captured = contexts.get(executionId);
        return CompletableFuture.supplyAsync(() -> {
            try (Scope scope = captured.makeCurrent()) {
                try {
                    return call.call();
                } catch (AgentException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new AgentException(ErrorCode.INTERNAL, ex.getMessage(), executionId, ex);
                }
            }
        }, VirtualIo.executor());
    }

    /**
     * 判断打开遥测时是否配置了导出器。
     *
     * @return 配置了非空白端点时为真
     */
    public boolean otlpExportEnabled() {
        return exporter != null;
    }

    /**
     * 返回导出器的实现类名，便于确认使用的是 HTTP 上的 OTLP 导出。
     *
     * @return 导出器类名；未启用时为空串
     */
    public String exporterClassName() {
        return exporter == null ? "" : exporter.getClass().getName();
    }

    /**
     * 关闭追踪提供器，从而结束仍挂着的导出处理器。副作用是释放导出器占用的资源。
     */
    @Override
    public void close() {
        tracerProvider.close();
    }

    /**
     * 把已结束跨度的名称、链路关系和属性抄成只读视图。
     *
     * @param span 追踪实现给出的跨度数据
     * @return 去掉实现类型后的视图
     */
    private static ObservedSpan observe(SpanData span) {
        Map<String, String> attributes = new LinkedHashMap<>();
        span.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), String.valueOf(value)));
        return new ObservedSpan(
                span.getName(),
                span.getSpanContext().getTraceId(),
                span.getSpanContext().getSpanId(),
                span.getParentSpanContext().getSpanId(),
                Map.copyOf(attributes));
    }

    /**
     * 把已结束的跨度留在内存里，供按执行标识回看。
     */
    private static final class SpanRecorder implements SpanProcessor {
        private final List<SpanData> finished = new CopyOnWriteArrayList<>();

        /**
         * 返回目前已经结束的跨度。
         *
         * @return 结束顺序下的跨度数据
         */
        List<SpanData> finished() {
            return finished;
        }

        /**
         * 跨度开始时不记录。没有副作用。
         *
         * @param parentContext 父级追踪上下文
         * @param span 刚开始、尚未结束的跨度
         */
        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        /**
         * 声明不需要开始回调。
         *
         * @return 始终为假
         */
        @Override
        public boolean isStartRequired() {
            return false;
        }

        /**
         * 跨度结束时把它的数据留在内存列表里。副作用是追加一条已结束跨度。
         *
         * @param span 刚刚结束的跨度
         */
        @Override
        public void onEnd(ReadableSpan span) {
            finished.add(span.toSpanData());
        }

        /**
         * 声明必须接收结束回调，否则内存里看不到跨度。
         *
         * @return 始终为真
         */
        @Override
        public boolean isEndRequired() {
            return true;
        }
    }
}
