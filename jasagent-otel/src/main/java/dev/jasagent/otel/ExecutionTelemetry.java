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

public final class ExecutionTelemetry implements AutoCloseable {
    static final String EXECUTION_ID = "jasagent.execution_id";
    static final String ROOT_SPAN = "jasagent.execution";

    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;
    private final SpanRecorder recorder;
    private final SpanExporter exporter;
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();
    private final Map<String, String> traceIds = new ConcurrentHashMap<>();
    private final Map<String, String> rootSpanIds = new ConcurrentHashMap<>();

    private ExecutionTelemetry(SdkTracerProvider tracerProvider, Tracer tracer, SpanRecorder recorder, SpanExporter exporter) {
        this.tracerProvider = tracerProvider;
        this.tracer = tracer;
        this.recorder = recorder;
        this.exporter = exporter;
    }

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

    public void startRoot(String executionId) {
        Span span = tracer.spanBuilder(ROOT_SPAN)
                .setAttribute(EXECUTION_ID, executionId)
                .startSpan();
        contexts.put(executionId, Context.root().with(span));
        traceIds.put(executionId, span.getSpanContext().getTraceId());
        rootSpanIds.put(executionId, span.getSpanContext().getSpanId());
        span.end();
    }

    public void startChild(String executionId, String name) {
        Context parent = contexts.get(executionId);
        Span span = tracer.spanBuilder(name)
                .setParent(parent)
                .setAttribute(EXECUTION_ID, executionId)
                .startSpan();
        span.end();
    }

    public String traceId(String executionId) {
        return traceIds.get(executionId);
    }

    public String rootSpanId(String executionId) {
        return rootSpanIds.get(executionId);
    }

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

    public boolean otlpExportEnabled() {
        return exporter != null;
    }

    public String exporterClassName() {
        return exporter == null ? "" : exporter.getClass().getName();
    }

    @Override
    public void close() {
        tracerProvider.close();
    }

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

    private static final class SpanRecorder implements SpanProcessor {
        private final List<SpanData> finished = new CopyOnWriteArrayList<>();

        List<SpanData> finished() {
            return finished;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            finished.add(span.toSpanData());
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }
    }
}
