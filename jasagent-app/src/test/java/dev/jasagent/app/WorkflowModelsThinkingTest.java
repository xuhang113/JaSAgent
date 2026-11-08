package dev.jasagent.app;

import dev.jasagent.llm.LlmSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowModelsThinkingTest {
    @Test
    void consolePropertyTurnsDeepseekThinkingOn() {
        LlmSettings off = new LlmSettings("placeholder-key", false, "", "", "", false, "", Duration.ofSeconds(60));
        assertFalse(off.deepseekThinking());
        LlmSettings on = WorkflowModels.applyThinking(off, Map.of("jasagent.deepseek.thinking", "true"));
        assertTrue(on.deepseekThinking());
        assertEquals("placeholder-key", on.deepseekApiKey());
        assertFalse(WorkflowModels.applyThinking(off, Map.of("jasagent.deepseek.thinking", "false")).deepseekThinking());
    }
}
