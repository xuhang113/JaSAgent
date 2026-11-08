package dev.jasagent.api.rag;

import java.util.concurrent.CompletionStage;

public interface RagPipeline {
    CompletionStage<RagResult> retrieve(RagQuery query);
}
