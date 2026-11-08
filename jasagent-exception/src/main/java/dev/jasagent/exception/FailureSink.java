package dev.jasagent.exception;

@FunctionalInterface
public interface FailureSink {
    void fail(String taskId, String status, Throwable error);
}
