package dev.jasagent.api.persist;

public record RunSnapshot(String executionId, String status, String checkpointNode, String traceId, String output) {
    public RunSnapshot withStatus(String nextStatus) {
        return new RunSnapshot(executionId, nextStatus, checkpointNode, traceId, output);
    }

    public RunSnapshot withOutput(String nextOutput) {
        return new RunSnapshot(executionId, status, checkpointNode, traceId, nextOutput);
    }
}
