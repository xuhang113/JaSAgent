package dev.jasagent.otel;

import dev.jasagent.api.otel.AgentSpan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OtelTracerTest {
    @Test
    void rootSpanNameAndExecutionAttribute() {
        OtelAgentTracer tracer = OtelAgentTracer.create(null);
        AgentSpan root = tracer.startRoot("exec-span");
        assertEquals("jasagent.execution", root.name());
        assertEquals("exec-span", root.attribute("jasagent.execution_id"));
        tracer.end(root);
    }

    @Test
    void childSpanSharesTraceId() {
        OtelAgentTracer tracer = OtelAgentTracer.create(null);
        AgentSpan root = tracer.startRoot("exec-share");
        AgentSpan child = tracer.startChild("workflow.node", "exec-share", root.traceId(), root.spanId());
        assertEquals(root.traceId(), child.traceId());
        assertEquals("exec-share", child.attribute("jasagent.execution_id"));
        tracer.end(child);
        tracer.end(root);
    }

    @Test
    void restartedTracerKeepsSavedTraceId() {
        OtelAgentTracer first = OtelAgentTracer.create(null);
        AgentSpan root = first.startRoot("exec-restart");
        String savedTrace = root.traceId();
        String savedSpan = root.spanId();
        first.end(root);
        OtelAgentTracer second = OtelAgentTracer.create(null);
        AgentSpan resumed = second.startChild("workflow.node", "exec-restart", savedTrace, savedSpan);
        assertEquals(savedTrace, resumed.traceId());
        second.end(resumed);
    }

    @Test
    void blankEndpointDoesNotExport() {
        OtelAgentTracer tracer = OtelAgentTracer.create("  ");
        assertFalse(tracer.exporting());
    }
}
