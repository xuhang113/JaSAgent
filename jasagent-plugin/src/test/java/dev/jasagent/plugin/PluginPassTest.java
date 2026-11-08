package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.HallucinationGuard;
import dev.jasagent.api.plugin.HallucinationGuards;
import dev.jasagent.api.plugin.NodePlugin;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginOutcome;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPassTest {
    @Test
    void pluginAcquiresPass() {
        CountingGate gate = new CountingGate(new Pass(PassDecision.SKIPPED, "agentpass-not-enabled"));
        join(new GatedPluginRunner().run(new OkPlugin("sample.plugin"), context("onNodeStart", "answer"), gate, false));
        assertEquals(1, gate.calls.get());
        assertEquals("sample.plugin", gate.last.pluginId());
    }

    @Test
    void pluginFailureContinues() {
        PluginOutcome outcome = join(new GatedPluginRunner().run(new BrokenPlugin(), context("afterModel", "answer"),
                new CountingGate(new Pass(PassDecision.SKIPPED, "agentpass-not-enabled")), false));
        assertTrue(outcome.continued());
    }

    @Test
    void pluginFailureFailsNode() {
        AgentException error = assertThrows(AgentException.class, () -> join(new GatedPluginRunner().run(
                new BrokenPlugin(), context("afterModel", "answer"),
                new CountingGate(new Pass(PassDecision.ALLOW, "ok")), true)));
        assertEquals(ErrorCode.PLUGIN, error.code());
    }

    @Test
    void groundingPluginCallsGuard() {
        AtomicReference<String> seen = new AtomicReference<>();
        HallucinationGuards.register((answer, evidence) -> {
            seen.set(answer);
            return true;
        });
        NodePlugin plugin = new GroundingPlugin();
        join(plugin.invoke(context("afterModel", "quoted-answer")));
        assertEquals("quoted-answer", seen.get());
    }

    @Test
    void serviceLoaderFindsGroundingPlugin() {
        boolean found = false;
        for (NodePlugin plugin : ServiceLoader.load(NodePlugin.class)) {
            if ("grounding.default".equals(plugin.id())) {
                found = true;
            }
        }
        assertTrue(found);
    }

    private static PluginContext context(String phase, String answer) {
        return new PluginContext(phase, "exec-1", "act", answer, "evidence");
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class CountingGate implements PassGate {
        private final Pass pass;
        private final AtomicInteger calls = new AtomicInteger();
        private PassRequest last;

        private CountingGate(Pass pass) {
            this.pass = pass;
        }

        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            calls.incrementAndGet();
            last = request;
            return CompletableFuture.completedFuture(pass);
        }
    }

    private static final class OkPlugin implements NodePlugin {
        private final String id;

        private OkPlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public CompletionStage<PluginOutcome> invoke(PluginContext context) {
            return CompletableFuture.completedFuture(PluginOutcome.ok());
        }
    }

    private static final class BrokenPlugin implements NodePlugin {
        @Override
        public String id() {
            return "broken.plugin";
        }

        @Override
        public CompletionStage<PluginOutcome> invoke(PluginContext context) {
            return CompletableFuture.failedFuture(new IllegalStateException("plugin broke"));
        }
    }
}
