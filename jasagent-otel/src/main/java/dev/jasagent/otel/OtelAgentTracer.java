package dev.jasagent.otel;

import dev.jasagent.api.otel.AgentSpan;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;

public final class OtelAgentTracer {
    private final Tracer tracer;
    private final boolean exporting;

    private OtelAgentTracer(Tracer tracer, boolean exporting) {
        this.tracer = tracer;
        this.exporting = exporting;
    }

    public static OtelAgentTracer create(String endpoint) {
        boolean exporting = endpoint != null && !endpoint.isBlank();
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        if (exporting) {
            builder.addSpanProcessor(BatchSpanProcessor.builder(
                    OtlpHttpSpanExporter.builder().setEndpoint(endpoint.trim()).build()).build());
        }
        SdkTracerProvider provider = builder.build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new OtelAgentTracer(openTelemetry.getTracer("jasagent"), exporting);
    }

    public boolean exporting() {
        return exporting;
    }

    public AgentSpan startRoot(String executionId) {
        Span span = tracer.spanBuilder("jasagent.execution")
                .setAttribute("jasagent.execution_id", executionId)
                .startSpan();
        return wrap("jasagent.execution", executionId, span);
    }

    public AgentSpan startChild(String name, String executionId, String traceId, String parentSpanId) {
        SpanContext remote = SpanContext.createFromRemoteParent(
                traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        Span span = tracer.spanBuilder(name)
                .setParent(Context.root().with(Span.wrap(remote)))
                .setAttribute("jasagent.execution_id", executionId)
                .startSpan();
        return wrap(name, executionId, span);
    }

    public void end(AgentSpan span) {
        if (span instanceof Wrapped wrapped) {
            wrapped.delegate().end();
        }
    }

    private AgentSpan wrap(String name, String executionId, Span span) {
        return new Wrapped(name, span.getSpanContext().getTraceId(), span.getSpanContext().getSpanId(), executionId, span);
    }

    private record Wrapped(String name, String traceId, String spanId, String executionId, Span delegate) implements AgentSpan {
        @Override
        public String attribute(String key) {
            if ("jasagent.execution_id".equals(key)) {
                return executionId;
            }
            return null;
        }
    }
}
