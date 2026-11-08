package dev.jasagent.rag;

import java.util.concurrent.CompletionStage;

public final class RagSearchTool {
    public static final String NAME = "rag.search";
    private final RagPipeline pipeline;

    public RagSearchTool(RagPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public CompletionStage<RagResult> call(RagQuery query) {
        return pipeline.search(query);
    }
}
