package dev.jasagent.llm;

import dev.jasagent.api.llm.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对正文里的工具对象和补全 JSON 里的 tool_calls 都能被还原，普通句子则被忽略。
 */
class ToolCallTextTest {
    /**
     * 断言一段只含工具名和参数对象的正文能还原成一次目录列举调用。
     */
    @Test
    void parsesABareToolObject() {
        List<LlmToolCall> calls = ToolCallText.parse("""
                {"tool":"list_directory","arguments":{"path":"."}}
                """);
        assertEquals(1, calls.size());
        assertEquals("list_directory", calls.get(0).name());
        assertTrue(calls.get(0).arguments().contains("."));
    }

    /**
     * 断言普通问候不会被当成工具调用。
     */
    @Test
    void proseIsNotAToolCall() {
        assertTrue(ToolCallText.parse("早上好！").isEmpty());
    }

    /**
     * 断言补全消息里的函数调用能还原出调用编号、工具名和参数文本。
     */
    @Test
    void readsOpenAiToolCalls() {
        List<LlmToolCall> calls = ToolCallText.fromCompletionJson("""
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"list_directory","arguments":"{\\"path\\":\\".\\"}"}}]}}]}
                """);
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).id());
        assertEquals("list_directory", calls.get(0).name());
        assertTrue(calls.get(0).arguments().contains("path"));
    }
}
