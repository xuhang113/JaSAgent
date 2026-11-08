package dev.jasagent.core;

import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.context.CorrectionSource;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.Embedding;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCorrectionTest {
    private static final Instant CREATED = Instant.parse("2026-11-08T00:00:00Z");

    @TempDir
    Path temp;

    @Test
    void defaultBudgetIs8000() {
        assertEquals(8000, ContextWindow.DEFAULT_BUDGET_TOKENS);
    }

    @Test
    void noteSitsBeforeCurrentUser() {
        Scripted model = script("SHORT");
        List<ChatMessage> messages = join(new ContextWindow(model).prepare(standard(ContextWindow.DEFAULT_BUDGET_TOKENS))).messages();
        assertEquals(0, model.requests.size());
        assertEquals("user", messages.get(messages.size() - 1).role());
        assertEquals("CURRENT", messages.get(messages.size() - 1).content());
        assertEquals("correction", messages.get(messages.size() - 2).role());
        assertEquals("NOTE-B", messages.get(messages.size() - 2).content());
        assertEquals("correction", messages.get(messages.size() - 3).role());
        assertEquals("NOTE", messages.get(messages.size() - 3).content());
    }

    @Test
    void unchangedWhenUnderBudget() {
        Scripted model = script("SHORT");
        List<ChatMessage> messages = join(new ContextWindow(model).prepare(standard(8000))).messages();
        String joined = joinText(messages);
        assertTrue(joined.contains("SHORT-PADDING"));
        assertTrue(joined.contains("SKILL-BODY-VERY-LONG"));
        assertTrue(messages.stream().noneMatch(message -> "summary".equals(message.role())));
    }

    @Test
    void noteRemainsAfterCompression() {
        List<ChatMessage> messages = join(new ContextWindow(script("SHORT")).prepare(standard(30))).messages();
        assertEquals("user", messages.get(messages.size() - 1).role());
        assertEquals("CURRENT", messages.get(messages.size() - 1).content());
        assertEquals("correction", messages.get(messages.size() - 2).role());
        assertEquals("NOTE-B", messages.get(messages.size() - 2).content());
        assertEquals("correction", messages.get(messages.size() - 3).role());
        assertEquals("NOTE", messages.get(messages.size() - 3).content());
    }

    @Test
    void protectedPartsStay() {
        List<ChatMessage> messages = join(new ContextWindow(script("SHORT")).prepare(standard(30))).messages();
        String joined = joinText(messages);
        assertTrue(joined.contains("RULE"));
        assertTrue(joined.contains("research"));
        assertTrue(joined.contains("查找资料"));
        assertTrue(joined.contains("BOARD"));
        assertTrue(joined.contains("RECENT-1"));
        assertTrue(joined.contains("RECENT-4"));
        assertTrue(joined.contains("exec-ctx"));
        List<ChatMessage> systems = messages.stream().filter(message -> "system".equals(message.role())).toList();
        assertEquals(1, systems.size());
        assertTrue(systems.get(0).content().contains("RULE"));
        assertTrue(systems.get(0).content().contains("exec-ctx"));
    }

    @Test
    void overBudgetUsesCompressRoute() {
        Scripted model = script("SHORT");
        ContextPrepared prepared = join(new ContextWindow(model).prepare(standard(30)));
        assertEquals(1, model.requests.size());
        ChatRequest request = model.requests.get(0);
        assertEquals(LlmTask.COMPRESS, request.task());
        assertEquals("compress", request.task().wireName());
        assertEquals(null, request.namedProvider());
        assertEquals("exec-ctx", request.executionId());
        assertEquals("SHORT", prepared.summary());
        assertEquals(1, prepared.messages().stream().filter(message -> "summary".equals(message.role()) && "SHORT".equals(message.content())).count());
    }

    @Test
    void summaryRequestOmitsProtectedText() {
        Scripted model = script("SHORT");
        join(new ContextWindow(model).prepare(standard(30)));
        ChatRequest request = model.requests.get(0);
        assertEquals(1, request.messages().size());
        assertEquals("user", request.messages().get(0).role());
        assertTrue(request.messages().get(0).content().startsWith("OLD-"));
        String sent = joinText(request.messages());
        assertFalse(sent.contains("RULE"));
        assertFalse(sent.contains("research"));
        assertFalse(sent.contains("查找资料"));
        assertFalse(sent.contains("SKILL-BODY-VERY-LONG"));
        assertFalse(sent.contains("NOTE"));
        assertFalse(sent.contains("BOARD"));
        assertFalse(sent.contains("exec-ctx"));
        assertFalse(sent.contains("RECENT-1"));
        assertFalse(sent.contains("RECENT-4"));
        assertFalse(sent.contains("SHORT-PADDING"));
    }

    @Test
    void noSummaryCallWhenNothingOld() {
        Scripted skillsOnly = script("SHORT");
        List<ChatMessage> recentOnly = List.of(
                ChatMessage.text("user", "RECENT-1"),
                ChatMessage.text("assistant", "RECENT-2"),
                ChatMessage.text("user", "RECENT-3"),
                ChatMessage.text("assistant", "RECENT-4"));
        String skills = joinText(join(new ContextWindow(skillsOnly).prepare(input(30, recentOnly, longSkill()))).messages());
        assertEquals(0, skillsOnly.requests.size());
        assertFalse(skills.contains("SKILL-BODY-VERY-LONG"));
        assertTrue(skills.contains("research"));
        assertTrue(skills.contains("查找资料"));
        assertTrue(skills.contains("RECENT-1"));

        Scripted toolsOnly = script("SHORT");
        List<ChatMessage> oldTool = new ArrayList<>();
        oldTool.add(ChatMessage.text("tool", "OLD-TOOL-BODY" + "X".repeat(400)));
        oldTool.add(ChatMessage.text("user", "RECENT-1"));
        oldTool.add(ChatMessage.text("assistant", "RECENT-2"));
        oldTool.add(ChatMessage.text("user", "RECENT-3"));
        oldTool.add(ChatMessage.text("assistant", "RECENT-4"));
        String collapsed = joinText(join(new ContextWindow(toolsOnly).prepare(input(40, oldTool, shortSkill("BODY")))).messages());
        assertEquals(0, toolsOnly.requests.size());
        assertTrue(collapsed.contains("tool: [collapsed]"));
        assertFalse(collapsed.contains("OLD-TOOL-BODY"));
    }

    @Test
    void oldToolResultsCollapse() {
        List<ChatMessage> turns = new ArrayList<>();
        turns.add(ChatMessage.text("user", "OLD-" + "Q".repeat(20)));
        turns.add(ChatMessage.text("tool", "OLD-TOOL-BODY-A-" + "Z".repeat(80)));
        turns.add(ChatMessage.text("tool", "OLD-TOOL-BODY-B-" + "Y".repeat(80)));
        turns.add(ChatMessage.text("user", "RECENT-1"));
        turns.add(ChatMessage.text("assistant", "RECENT-2"));
        turns.add(ChatMessage.text("user", "RECENT-3"));
        turns.add(ChatMessage.text("assistant", "RECENT-4"));
        List<ChatMessage> messages = join(new ContextWindow(script("X".repeat(400))).prepare(input(40, turns, longSkill()))).messages();
        assertEquals(2, messages.stream().filter(message -> "tool: [collapsed]".equals(message.content())).count());
        assertFalse(joinText(messages).contains("OLD-TOOL-BODY"));
        assertTrue(joinText(messages).contains("RECENT-1"));
    }

    @Test
    void recentToolResultsStay() {
        List<ChatMessage> turns = new ArrayList<>();
        turns.add(ChatMessage.text("user", "OLD"));
        turns.add(ChatMessage.text("tool", "OLD-TOOL-BODY" + "X".repeat(400)));
        turns.add(ChatMessage.text("user", "R1"));
        turns.add(ChatMessage.text("assistant", "R2"));
        turns.add(ChatMessage.text("user", "R3"));
        turns.add(ChatMessage.text("tool", "RECENT-TOOL-BODY"));
        String joined = joinText(join(new ContextWindow(script("S")).prepare(input(50, turns, shortSkill("BODY")))).messages());
        assertTrue(joined.contains("RECENT-TOOL-BODY"));
        assertTrue(joined.contains("tool: [collapsed]"));
        assertFalse(joined.contains("OLD-TOOL-BODY"));
    }

    @Test
    void skillsShrinkToNameAndDescription() {
        String joined = joinText(join(new ContextWindow(script("X".repeat(800))).prepare(standard(20))).messages());
        assertTrue(joined.contains("research"));
        assertTrue(joined.contains("查找资料"));
        assertFalse(joined.contains("SKILL-BODY-VERY-LONG"));
    }

    @Test
    void skillsStayWhenCollapseFits() {
        List<ChatMessage> turns = new ArrayList<>();
        turns.add(ChatMessage.text("user", "OLD"));
        turns.add(ChatMessage.text("tool", "T" + "X".repeat(400)));
        turns.add(ChatMessage.text("user", "1"));
        turns.add(ChatMessage.text("assistant", "2"));
        turns.add(ChatMessage.text("user", "3"));
        turns.add(ChatMessage.text("assistant", "4"));
        String joined = joinText(join(new ContextWindow(script("S")).prepare(input(40, turns, shortSkill("BODY")))).messages());
        assertTrue(joined.contains("BODY"));
        assertTrue(joined.contains("tool: [collapsed]"));
        assertFalse(joined.contains("X".repeat(50)));
    }

    @Test
    void forbiddenPartsStayWhenStillOverBudget() {
        List<ChatMessage> messages = join(new ContextWindow(script("X".repeat(800))).prepare(standard(20))).messages();
        assertTrue(messages.stream().anyMatch(message -> "correction".equals(message.role()) && "NOTE".equals(message.content())));
        assertTrue(messages.stream().anyMatch(message -> "correction".equals(message.role()) && "NOTE-B".equals(message.content())));
        String system = messages.stream().filter(message -> "system".equals(message.role())).findFirst().orElseThrow().content();
        assertTrue(system.contains("BOARD"));
        assertTrue(system.contains("exec-ctx"));
    }

    @Test
    void noteStoredInUserDirAndSnapshot() throws Exception {
        List<CorrectionNote> snapshot = new ArrayList<>();
        CorrectionNote note = note("n1", "sess-1", CorrectionSource.USER, "NOTE \"keep\"");
        join(new CorrectionStore().append(temp.resolve("jasagent"), note, snapshot::add));
        String stored = Files.readString(temp.resolve("jasagent").resolve("corrections").resolve("sess-1").resolve("n1.json"));
        assertTrue(stored.contains("\"noteId\":\"n1\""));
        assertTrue(stored.contains("\"sessionId\":\"sess-1\""));
        assertTrue(stored.contains("\"executionId\":\"exec-ctx\""));
        assertTrue(stored.contains("\"createdAt\":\"2026-11-08T00:00:00Z\""));
        assertTrue(stored.contains("\"source\":\"USER\""));
        assertTrue(stored.contains("\"text\":\"NOTE \\\"keep\\\"\""));
        assertEquals(note, snapshot.get(0));
        assertEquals("NOTE \"keep\"", snapshot.get(0).text());
    }

    @Test
    void fourSourcesRoundTrip() throws Exception {
        assertEquals(
                List.of(CorrectionSource.USER, CorrectionSource.TOOL_CONTRADICTION, CorrectionSource.GROUNDING, CorrectionSource.EVALUATION),
                List.of(CorrectionSource.values()));
        Path root = temp.resolve("jasagent");
        for (CorrectionSource source : CorrectionSource.values()) {
            join(new CorrectionStore().append(root, note(source.name(), "sess-1", source, source.name()), ignored -> {
            }));
            String stored = Files.readString(root.resolve("corrections").resolve("sess-1").resolve(source.name() + ".json"));
            assertTrue(stored.contains("\"source\":\"" + source.name() + "\""));
        }
    }

    @Test
    void invalidNoteIsRejected() {
        Path root = temp.resolve("jasagent");
        List<CorrectionNote> snapshot = new ArrayList<>();
        CorrectionStore store = new CorrectionStore();
        assertInternal(() -> join(store.append(root, null, snapshot::add)));
        assertInternal(() -> join(store.append(root, note(" ", "sess-1", CorrectionSource.USER, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(root, note("n1", " ", CorrectionSource.USER, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(root, note("n1", "sess-1", CorrectionSource.USER, " "), snapshot::add)));
        assertInternal(() -> join(store.append(root, new CorrectionNote("n1", "sess-1", " ", CREATED, CorrectionSource.USER, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(root, new CorrectionNote("n1", "sess-1", "exec-ctx", CREATED, null, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(root, new CorrectionNote("n1", "sess-1", "exec-ctx", null, CorrectionSource.USER, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(root, note("../n", "sess-1", CorrectionSource.USER, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(root, note("n1", "../x", CorrectionSource.USER, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(root, note("a/b", "sess-1", CorrectionSource.USER, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(null, note("n1", "sess-1", CorrectionSource.USER, "NOTE"), snapshot::add)));
        assertInternal(() -> join(store.append(root, note("n1", "sess-1", CorrectionSource.USER, "NOTE"), null)));
        assertTrue(snapshot.isEmpty());
        assertTrue(Files.notExists(root) || !Files.exists(root.resolve("corrections")));
    }

    @Test
    void toolContradictionKeepsToolResult() {
        assertConflict("I think \"99:99\" is right");
        assertConflict("记忆是“99:99”");
    }

    @Test
    void quotedFragmentInEvidenceAddsNoNote() {
        List<ChatMessage> history = List.of(ChatMessage.text("assistant", "the clock says \"12:00 UTC\""));
        List<CorrectionNote> inTool = new ArrayList<>();
        List<ChatMessage> kept = new ToolMemory().reconcile(history, "12:00 UTC", "hello", inTool, "sess-1", "exec-ctx");
        assertTrue(inTool.isEmpty());
        assertEquals("12:00 UTC", kept.get(kept.size() - 1).content());

        List<CorrectionNote> inUser = new ArrayList<>();
        new ToolMemory().reconcile(List.of(ChatMessage.text("assistant", "say \"hello\"")), "12:00 UTC", "hello there", inUser, "sess-1", "exec-ctx");
        assertTrue(inUser.isEmpty());

        List<CorrectionNote> noQuote = new ArrayList<>();
        List<ChatMessage> plain = new ToolMemory().reconcile(List.of(ChatMessage.text("assistant", "no quotes")), "12:00 UTC", "hello", noQuote, "sess-1", "exec-ctx");
        assertTrue(noQuote.isEmpty());
        assertEquals("12:00 UTC", plain.get(plain.size() - 1).content());

        List<ChatMessage> original = new ArrayList<>();
        original.add(ChatMessage.text("assistant", "I think \"99:99\" is right"));
        List<CorrectionNote> blankTool = new ArrayList<>();
        List<ChatMessage> unchanged = new ToolMemory().reconcile(original, "  ", "hello", blankTool, "sess-1", "exec-ctx");
        assertTrue(blankTool.isEmpty());
        assertEquals(original.size(), unchanged.size());
    }

    @Test
    void rejectsBlankExecutionAndBadBudget() {
        Scripted model = script("SHORT");
        ContextWindow window = new ContextWindow(model);
        assertInternal(() -> join(window.prepare(input(0, standardTurns(), longSkill()))));
        ContextInput blank = new ContextInput(" ", "助手", "RULE", longSkill(), "BOARD", standardNotes(), standardTurns(), "CURRENT", 30);
        assertInternal(() -> join(window.prepare(blank)));
        assertEquals(0, model.requests.size());
    }

    @Test
    void nullModelRejected() {
        AgentException ex = assertThrows(AgentException.class, () -> new ContextWindow(null));
        assertEquals(ErrorCode.INTERNAL, ex.code());
    }

    private static void assertConflict(String assistant) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.text("assistant", assistant));
        List<CorrectionNote> notes = new ArrayList<>();
        List<ChatMessage> next = new ToolMemory().reconcile(history, "12:00 UTC", "hello", notes, "sess-1", "exec-ctx");
        assertEquals(1, history.size());
        assertEquals(CorrectionSource.TOOL_CONTRADICTION, notes.get(0).source());
        assertEquals("sess-1", notes.get(0).sessionId());
        assertEquals("exec-ctx", notes.get(0).executionId());
        assertTrue(notes.get(0).text().startsWith("以工具结果为准："));
        assertTrue(notes.get(0).text().contains("12:00 UTC"));
        assertEquals("tool", next.get(next.size() - 1).role());
        assertEquals("12:00 UTC", next.get(next.size() - 1).content());
    }

    private static ContextInput standard(int budget) {
        return input(budget, standardTurns(), longSkill());
    }

    private static List<ChatMessage> standardTurns() {
        List<ChatMessage> turns = new ArrayList<>();
        turns.add(ChatMessage.text("user", "OLD-" + "Q".repeat(300)));
        turns.add(ChatMessage.text("tool", "SHORT-PADDING-" + "Z".repeat(20)));
        turns.add(ChatMessage.text("user", "RECENT-1"));
        turns.add(ChatMessage.text("assistant", "RECENT-2"));
        turns.add(ChatMessage.text("user", "RECENT-3"));
        turns.add(ChatMessage.text("assistant", "RECENT-4"));
        return turns;
    }

    private static List<SkillText> longSkill() {
        return List.of(new SkillText("research", "查找资料", "SKILL-BODY-VERY-LONG-" + "S".repeat(400)));
    }

    private static List<SkillText> shortSkill(String body) {
        return List.of(new SkillText("research", "查找资料", body));
    }

    private static List<CorrectionNote> standardNotes() {
        return List.of(
                note("n1", "sess-1", CorrectionSource.USER, "NOTE"),
                note("n2", "sess-1", CorrectionSource.USER, "NOTE-B"));
    }

    private static ContextInput input(int budget, List<ChatMessage> turns, List<SkillText> skills) {
        return new ContextInput("exec-ctx", "助手", "RULE", skills, "BOARD", standardNotes(), turns, "CURRENT", budget);
    }

    private static CorrectionNote note(String noteId, String sessionId, CorrectionSource source, String text) {
        return new CorrectionNote(noteId, sessionId, "exec-ctx", CREATED, source, text);
    }

    private static Scripted script(String summary) {
        return new Scripted(summary);
    }

    private static String joinText(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            builder.append(message.content()).append('\n');
        }
        return builder.toString();
    }

    private static void assertInternal(ThrowingCall call) {
        AgentException ex = assertThrows(AgentException.class, call::run);
        assertEquals(ErrorCode.INTERNAL, ex.code());
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static final class Scripted implements ChatModel {
        private final String summary;
        private final List<ChatRequest> requests = new ArrayList<>();

        private Scripted(String summary) {
            this.summary = summary;
        }

        @Override
        public CompletionStage<ChatResponse> complete(ChatRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(new ChatResponse(
                    "deepseek",
                    "deepseek-flash",
                    ChatMessage.text("assistant", summary),
                    new TokenUsage(1, 1),
                    1,
                    false));
        }

        @Override
        public CompletionStage<Embedding> embed(String text, String executionId) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "unused"));
        }
    }
}
