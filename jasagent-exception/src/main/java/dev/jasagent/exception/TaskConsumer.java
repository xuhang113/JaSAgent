package dev.jasagent.exception;

public final class TaskConsumer {
    public void consume(String taskId, Runnable action, FailureSink sink) {
        try {
            action.run();
        } catch (Throwable error) {
            sink.fail(taskId, "FAILED", error);
        }
    }
}
