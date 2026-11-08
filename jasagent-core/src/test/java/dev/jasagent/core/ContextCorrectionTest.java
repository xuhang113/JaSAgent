package dev.jasagent.core;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.Embedding;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.TokenUsage;
import dev.jasagent.api.workflow.CorrectionNote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCorrectionTest {
    @TempDir
    Path temp;

    @Test
    void noteSitsBeforeCurrentUser() {
        List<ChatMessage> messages = new ContextWindow(script("SHORT")).prepare(sample(8_000, "SHORT", false)).messages();
        assertTrue(messages.get(messages.size() - 2).content().contains("NOTE"));
        assertEquals("user", messages.get(messages.size() - 1).role());
        assertTrue(messages.get(messages.size() - 1).content().contains("CURRENT"));
    }

    @Test
    void noteRemainsAfterCompression() {
        List<ChatMessage> messages = new ContextWindow(script("SHORT")).prepare(sample(30, "SHORT", false)).messages();
        assertTrue(messages.get(messages.size() - 2).content().contains("NOTE"));
        assertTrue(messages.get(messages.size() - 1).content().contains("CURRENT"));
    }

    @Test
    void protectedPartsStay() {
        String joined = join(new ContextWindow(script("SHORT")).prepare(sample(30, "SHORT", false)).messages());
        assertTrue(joined.contains("RULE"));
        assertTrue(joined.contains("BOARD"));
        assertTrue(joined.contains("RECENT-1"));
        assertTrue(joined.contains("RECENT-4"));
    }

    @Test
    void overBudgetUsesCompressRoute() {
        Scripted model = script("SHORT");
        new ContextWindow(model).prepare(sample(30, "SHORT", false));
        assertEquals(LlmTask.COMPRESS, model.tasks.get(0));
    }

    @Test
    void oldToolResultsCollapse() {
        ContextPrepared prepared = new ContextWindow(script("X".repeat(400))).prepare(sample(40, "OLD-TOOL-BODY", true));
        assertTrue(prepared.messages().stream().anyMatch(message -> message.content().startsWith("tool:")));
        assertFalse(join(prepared.messages()).contains("OLD-TOOL-BODY"));
    }

    @Test
    void skillsShrinkToName() {
        String joined = join(new ContextWindow(script("X".repeat(800))).prepare(sample(20, "OLD-TOOL-BODY", true)).messages());
        assertTrue(joined.contains("research"));
        assertTrue(joined.contains("查找资料"));
        assertFalse(joined.contains("SKILL-BODY-VERY-LONG"));
    }

    @Test
    void executionIdSurvivesCompression() {
        String joined = join(new ContextWindow(script("SHORT")).prepare(sample(30, "SHORT", false)).messages());
        assertTrue(joined.contains("exec-ctx"));
    }

    @Test
    void noteStoredInUserDirAndSnapshot() throws Exception {
        List<CorrectionNote> snapshot = new ArrayList<>();
        CorrectionNote note = new CorrectionNote("n1", "sess-1", "exec-ctx", Instant.parse("2026-11-08T00:00:00Z"), "USER", "NOTE");
        new CorrectionStore().append(temp.resolve("jasagent"), note, snapshot::add);
        String stored = Files.readString(temp.resolve("jasagent").resolve("corrections").resolve("sess-1").resolve("n1.json"));
        assertTrue(stored.contains("NOTE"));
        assertEquals("NOTE", snapshot.get(0).text());
    }

    @Test
    void toolContradictionKeepsToolResult() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.text("assistant", "I think \"99:99\" is right"));
        List<CorrectionNote> notes = new ArrayList<>();
        List<ChatMessage> next = new ToolMemory().reconcile(messages, "12:00 UTC", "hello", notes, "sess-1", "exec-ctx");
        assertEquals("TOOL_CONTRADICTION", notes.get(0).source());
        assertTrue(join(next).contains("12:00 UTC"));
    }

    private static ContextInput sample(int budget, String oldTool, boolean longSummaryPressure) {
        List<ChatMessage> recent = new ArrayList<>();
        recent.add(ChatMessage.text("tool", oldTool + "-PADDING-" + "Z".repeat(longSummaryPressure ? 200 : 20)));
        recent.add(ChatMessage.text("user", "RECENT-1"));
        recent.add(ChatMessage.text("assistant", "RECENT-2"));
        recent.add(ChatMessage.text("user", "RECENT-3"));
        recent.add(ChatMessage.text("assistant", "RECENT-4"));
        recent.add(0, ChatMessage.text("user", "OLD-" + "Q".repeat(300)));
        return new ContextInput(
                "exec-ctx",
                "RULE",
                List.of(new SkillText("research", "查找资料", "SKILL-BODY-VERY-LONG-" + "S".repeat(400))),
                "BOARD",
                List.of(new CorrectionNote("n1", "sess-1", "exec-ctx", Instant.parse("2026-11-08T00:00:00Z"), "USER", "NOTE")),
                recent,
                "CURRENT",
                budget);
    }

    private static Scripted script(String summary) {
        return new Scripted(summary);
    }

    private static String join(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            builder.append(message.content()).append('\n');
        }
        return builder.toString();
    }

    private static final class Scripted implements ChatModel {
        private final String summary;
        private final List<LlmTask> tasks = new ArrayList<>();

        private Scripted(String summary) {
            this.summary = summary;
        }

        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            tasks.add(request.task());
            return CompletableFuture.completedFuture(new ChatResponse("deepseek", "deepseek-flash", ChatMessage.text("assistant", summary), new TokenUsage(1, 1), 1, false));
        }

        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }
}
