package dev.jasagent.llm;

import dev.jasagent.api.llm.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallTextTest {
    @Test
    void parsesABareToolObject() {
        List<LlmToolCall> calls = ToolCallText.parse("""
                {"tool":"list_directory","arguments":{"path":"."}}
                """);
        assertEquals(1, calls.size());
        assertEquals("list_directory", calls.get(0).name());
        assertTrue(calls.get(0).arguments().contains("."));
    }

    @Test
    void proseIsNotAToolCall() {
        assertTrue(ToolCallText.parse("早上好！").isEmpty());
    }

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
