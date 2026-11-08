package dev.jasagent.api.pass;

import java.util.concurrent.CompletionStage;

public interface PassGate {
    CompletionStage<Pass> acquire(PassRequest request);
}
