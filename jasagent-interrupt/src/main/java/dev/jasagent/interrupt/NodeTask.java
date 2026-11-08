package dev.jasagent.interrupt;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public final class NodeTask {
    private final String id;
    private final Function<String, CompletionStage<String>> action;

    private NodeTask(String id, Function<String, CompletionStage<String>> action) {
        this.id = id;
        this.action = action;
    }

    public static NodeTask of(String id, Function<String, CompletionStage<String>> action) {
        return new NodeTask(id, action);
    }

    public String id() {
        return id;
    }

    CompletionStage<String> run(String executionId) {
        return action.apply(executionId);
    }
}
