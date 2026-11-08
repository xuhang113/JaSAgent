package dev.jasagent.agentpass;

import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisabledPassGateTest {
    @Test
    void disabledPassGateSkips() throws Exception {
        Pass pass = new DisabledPassGate()
                .acquire(new PassRequest("user-a", "exec-1", "external.echo", null, "hash"))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(PassDecision.SKIPPED, pass.decision());
        assertEquals("agentpass-not-enabled", pass.reason());
    }
}
