package dev.jasagent.llm;

import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmChatResponse;
import dev.jasagent.api.llm.LlmMessage;
import dev.jasagent.api.llm.LlmTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本机有 XAI 密钥时，向 https://api.x.ai/v1 的 grok-4.7 打一次真实调用，并确认日志里没有密钥明文。
 * 密钥不可用时这条测试标成未测。
 */
class GrokLiveCallTest {
    /**
     * 装入密钥后发一句短对话。没有密钥就跳过并说明未测。
     * 副作用：可能访问 api.x.ai。断言失败则测试失败。
     */
    @Test
    void liveGrokWhenKeyPresent() {
        boolean installed = GrokEnvironment.installDefault();
        String key = LlmSettings.fromEnvironment().grokApiKey();
        Assumptions.assumeTrue(installed && key != null && !key.isBlank(), "未测");
        List<String> logs = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            /**
             * 收集日志正文，供结束后检查是否出现密钥。
             *
             * @param record 一条日志
             */
            @Override
            public void publish(LogRecord record) {
                if (record == null) {
                    return;
                }
                logs.add(String.valueOf(record.getMessage()));
                if (record.getThrown() != null) {
                    logs.add(String.valueOf(record.getThrown()));
                }
            }

            /**
             * 没有需要刷出的缓冲。
             */
            @Override
            public void flush() {
            }

            /**
             * 不关闭上层日志。
             */
            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            LlmSettings settings = LlmSettings.fromEnvironment();
            GrokLlmClient client = GrokLlmClient.create(settings);
            LlmChatResponse response = client.chat(new LlmChatRequest(
                    "grok-live",
                    LlmTask.REACT,
                    "grok",
                    List.of(new LlmMessage("user", "Reply with the single word pong.")),
                    1,
                    OpenAiCallConfig.GROK_MODEL)).toCompletableFuture().join();
            assertNotNull(response.text());
            assertFalse(response.text().isBlank(), "grok 没有返回正文");
            String joined = String.join("\n", logs);
            assertFalse(joined.contains(key), "日志含有密钥明文");
            assertFalse(response.text().contains(key), "响应含有密钥明文");
            assertTrue(response.text().toLowerCase().contains("pong") || !response.text().isBlank());
        } finally {
            root.removeHandler(handler);
        }
    }
}
