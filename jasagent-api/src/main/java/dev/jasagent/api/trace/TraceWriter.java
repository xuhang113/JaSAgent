package dev.jasagent.api.trace;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public interface TraceWriter {
    CompletionStage<Void> event(Path workspace, String executionId, TraceEvent event);

    CompletionStage<String> readLog(Path workspace, String executionId);

    CompletionStage<String> readEvents(Path workspace, String executionId);
}
