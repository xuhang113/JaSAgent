package dev.jasagent.app;

import dev.jasagent.llm.LlmSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认运行配置里的思考开关能打开 DeepSeek 思考，且不会改掉密钥或把 false 误当成开启。
 */
class WorkflowModelsThinkingTest {
    /**
     * 用一把占位密钥和关闭的思考创建设置。配置为 true 后思考打开且密钥不变；配置为 false 时思考仍关闭。
     * 副作用：无外部写入。断言失败则测试失败。
     */
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
