package dev.jasagent.api.trace;

import dev.jasagent.api.event.TraceEvent;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public interface TraceWriter {
    CompletionStage<Void> event(Path workspace, TraceEvent event);

    CompletionStage<String> readLog(Path workspace, String executionId);
}
