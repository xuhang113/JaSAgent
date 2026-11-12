package dev.jasagent.trace.lineage;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 核对用户请求和引用能写入 jasagent，并按 executionId 原样查回。
 * 每条会写库的测试都用自己的执行标识，结束后删掉这些行。
 */
class RequestLineageTest {
    /**
     * 本类测试写过的执行标识，结束后按它删除。
     */
    private final List<String> executionIds = new ArrayList<>();

    /**
     * 被测调用点。测试之间共用这一份，它不保存口令。
     */
    private final RequestLineage lineage = new RequestLineage();

    /**
     * 删掉本条测试写下的溯源行。
     * 删除失败时让本条测试失败，避免行留在 jasagent 库里还被当成通过。
     */
    @AfterEach
    void deleteRows() {
        for (String executionId : executionIds) {
            lineage.deleteExecution(executionId);
        }
    }

    /**
     * 没带 traceId 时生成一个，并贯穿网关、执行、模型调用和工具调用。
     * 按 executionId 能查回租户、用户、会话和 agent。断言失败则本测试失败。
     */
    @Test
    void requestCarriesGeneratedTraceAndIdentity() {
        String executionId = executionId();
        RequestTrace stored = join(lineage.recordRequest(new RequestTraceCommand(
                null,
                executionId,
                "租户甲",
                "user-a",
                "session-a",
                "agent-main@127.0.0.1",
                "ai-gateway",
                List.of(new ModelCallTrace("model-1", "deepseek", "deepseek-flash", null, null)),
                List.of(new ToolCallTrace("tool-1", "rag.search", null, null)))));
        assertEquals(LineageTables.GENERATED_TRACE_ID_LENGTH, stored.traceId().length());
        assertTrue(stored.traceId().matches("[0-9a-f]{32}"), stored.traceId());
        ExecutionLineage found = join(lineage.findByExecutionId(executionId));
        assertTrue(found.recorded());
        RequestTrace request = found.request();
        assertEquals(stored.traceId(), request.traceId());
        assertEquals(executionId, request.executionId());
        assertEquals("租户甲", request.tenantId());
        assertEquals("user-a", request.userId());
        assertEquals("session-a", request.sessionId());
        assertEquals("agent-main@127.0.0.1", request.agentId());
        assertEquals("ai-gateway", request.gateway());
        assertEquals(1, request.modelCalls().size());
        ModelCallTrace model = request.modelCalls().get(0);
        assertEquals("model-1", model.callId());
        assertEquals("deepseek", model.provider());
        assertEquals("deepseek-flash", model.model());
        assertEquals(stored.traceId(), model.traceId());
        assertEquals(executionId, model.executionId());
        assertEquals(1, request.toolCalls().size());
        ToolCallTrace tool = request.toolCalls().get(0);
        assertEquals("tool-1", tool.callId());
        assertEquals("rag.search", tool.toolName());
        assertEquals(stored.traceId(), tool.traceId());
        assertEquals(executionId, tool.executionId());
        assertTrue(found.citations().isEmpty());
    }

    /**
     * 调用方已经有 traceId 时原样保存，不另生成。断言失败则本测试失败。
     */
    @Test
    void suppliedTraceIdIsKept() {
        String executionId = executionId();
        String traceId = "trace-from-gateway-001";
        RequestTrace stored = join(lineage.recordRequest(request(executionId, traceId, "model-keep", "tool-keep")));
        ExecutionLineage found = join(lineage.findByExecutionId(executionId));
        assertEquals(traceId, stored.traceId());
        assertEquals(traceId, found.request().traceId());
        assertEquals(traceId, found.request().modelCalls().get(0).traceId());
        assertEquals(traceId, found.request().toolCalls().get(0).traceId());
    }

    /**
     * 示例 RAG 的命中要能从 executionId 追到这次回答，并带上服务、文档、片段和会话。
     * 同一条命中再记一次不新开引用行。断言失败则本测试失败。
     */
    @Test
    void ragHitStaysWithTheAnswer() {
        String executionId = executionId();
        String sessionId = "session-rag";
        String answerId = "answer-rag";
        join(lineage.recordRequest(request(executionId, "trace-rag-answer", "model-rag", "tool-rag")));
        RagHitCitation hit = RagHitCitation.rag("notes/guide.md", "workspace:notes/guide.md#0");
        List<CitationTrace> first = join(lineage.recordRagHits(executionId, sessionId, answerId, List.of(hit)));
        List<CitationTrace> second = join(lineage.recordRagHits(executionId, sessionId, answerId, List.of(hit)));
        assertEquals(1, first.size());
        assertEquals(first.get(0).citationId(), second.get(0).citationId());
        ExecutionLineage found = join(lineage.findByExecutionId(executionId));
        assertEquals(1, found.citations().size());
        CitationTrace citation = found.citations().get(0);
        assertEquals(executionId, citation.executionId());
        assertEquals(sessionId, citation.sessionId());
        assertEquals(answerId, citation.answerId());
        assertEquals(LineageTables.SERVICE_RAG, citation.service());
        assertEquals("notes/guide.md", citation.documentId());
        assertEquals("workspace:notes/guide.md#0", citation.fragmentId());
        assertEquals("trace-rag-answer", citation.traceId());
    }

    /**
     * 直接记录的引用同样能按 executionId 查回。断言失败则本测试失败。
     */
    @Test
    void citationIsFoundByExecutionId() {
        String executionId = executionId();
        join(lineage.recordRequest(request(executionId, "trace-cite", "model-cite", "tool-cite")));
        CitationTrace stored = join(lineage.recordCitation(new CitationTraceCommand(
                executionId,
                "session-cite",
                "answer-cite",
                "docs",
                "handbook",
                "fragment-9",
                null)));
        ExecutionLineage found = join(lineage.findByExecutionId(executionId));
        assertEquals(1, found.citations().size());
        CitationTrace citation = found.citations().get(0);
        assertEquals(stored.citationId(), citation.citationId());
        assertEquals("docs", citation.service());
        assertEquals("handbook", citation.documentId());
        assertEquals("fragment-9", citation.fragmentId());
        assertEquals("session-cite", citation.sessionId());
        assertEquals("answer-cite", citation.answerId());
        assertEquals(executionId, citation.executionId());
        assertEquals("trace-cite", citation.traceId());
    }

    /**
     * 引用先写下、请求后记下时，空着的 traceId 会补成请求上的值，片段仍挂在原来的回答上。
     * 断言失败则本测试失败。
     */
    @Test
    void citationBeforeRequestInheritsTrace() {
        String executionId = executionId();
        join(lineage.recordCitation(new CitationTraceCommand(
                executionId,
                "session-early",
                "answer-early",
                LineageTables.SERVICE_RAG,
                "early.md",
                "chunk-early",
                null)));
        join(lineage.recordRequest(request(executionId, "trace-early", "model-early", "tool-early")));
        ExecutionLineage found = join(lineage.findByExecutionId(executionId));
        assertEquals("trace-early", found.request().traceId());
        assertEquals(1, found.citations().size());
        assertEquals("trace-early", found.citations().get(0).traceId());
        assertEquals("answer-early", found.citations().get(0).answerId());
        assertEquals("chunk-early", found.citations().get(0).fragmentId());
    }

    /**
     * 再记一次请求只留下新的模型调用，另一条执行的引用不会混进来。断言失败则本测试失败。
     */
    @Test
    void secondRecordReplacesCallsWithoutMixingExecutions() {
        String executionId = executionId();
        String otherId = executionId();
        join(lineage.recordRequest(request(executionId, "trace-first", "model-old", "tool-old")));
        join(lineage.recordCitation(new CitationTraceCommand(
                executionId, "session-first", "answer-first", "rag", "doc-first", "frag-first", null)));
        join(lineage.recordRequest(request(otherId, "trace-other", "model-other", "tool-other")));
        join(lineage.recordCitation(new CitationTraceCommand(
                otherId, "session-other", "answer-other", "rag", "doc-other", "frag-other", null)));
        join(lineage.recordRequest(new RequestTraceCommand(
                "trace-second",
                executionId,
                "租户乙",
                "user-b",
                "session-b",
                "agent-worker@10.0.0.8",
                "ai-gateway",
                List.of(new ModelCallTrace("model-new", "ai-gateway", "gateway-model", null, null)),
                List.of())));
        ExecutionLineage found = join(lineage.findByExecutionId(executionId));
        assertEquals("trace-second", found.request().traceId());
        assertEquals("租户乙", found.request().tenantId());
        assertEquals("user-b", found.request().userId());
        assertEquals("session-b", found.request().sessionId());
        assertEquals("agent-worker@10.0.0.8", found.request().agentId());
        assertEquals(List.of("model-new"), found.request().modelCalls().stream().map(ModelCallTrace::callId).toList());
        assertTrue(found.request().toolCalls().isEmpty());
        assertEquals(List.of("frag-first"), found.citations().stream().map(CitationTrace::fragmentId).toList());
        ExecutionLineage other = join(lineage.findByExecutionId(otherId));
        assertEquals(List.of("frag-other"), other.citations().stream().map(CitationTrace::fragmentId).toList());
        assertEquals("model-other", other.request().modelCalls().get(0).callId());
    }

    /**
     * 没有这行执行时查不到请求，引用也是空的。断言失败则本测试失败。
     */
    @Test
    void unknownExecutionHasNoLineage() {
        ExecutionLineage found = join(lineage.findByExecutionId(executionId()));
        assertFalse(found.recorded());
        assertNull(found.request());
        assertTrue(found.citations().isEmpty());
    }

    /**
     * executionId 为空时直接失败，错误码是 INTERNAL，并且不写库。断言失败则本测试失败。
     */
    @Test
    void blankExecutionIdIsRejected() {
        AgentException error = assertThrows(AgentException.class, () -> join(lineage.recordRequest(
                new RequestTraceCommand("trace", "  ", "tenant", "user", "session", "agent", "gateway", List.of(), List.of()))));
        assertEquals(ErrorCode.INTERNAL, error.code());
        assertEquals("executionId 不能为空", error.getMessage());
    }

    /**
     * 连接说明和落库字段都不含口令，Bearer 也不会留下。断言失败则本测试失败。
     */
    @Test
    void secretStaysOutOfLineage() {
        String secret = password();
        String description = lineage.endpointDescription();
        if (description.contains(secret) || description.toLowerCase().contains("password=")) {
            fail("连接说明含有口令");
        }
        AgentException rejected = assertThrows(AgentException.class, () -> join(lineage.recordRequest(new RequestTraceCommand(
                null,
                executionId(),
                "tenant",
                "user",
                "session",
                "agent",
                secret,
                List.of(),
                List.of()))));
        if (rejected.getMessage() != null && rejected.getMessage().contains(secret)) {
            fail("异常消息含有口令");
        }
        assertEquals(ErrorCode.INTERNAL, rejected.code());
        String executionId = executionId();
        join(lineage.recordRequest(new RequestTraceCommand(
                null,
                executionId,
                "tenant",
                "user",
                "session",
                "agent",
                "gate Bearer " + secret,
                List.of(new ModelCallTrace("model-secret", "deepseek", "m-" + secret + "-tail", null, null)),
                List.of())));
        ExecutionLineage found = join(lineage.findByExecutionId(executionId));
        String stored = found.request().gateway() + "\n" + found.request().modelCalls().get(0).model();
        if (stored.contains(secret) || stored.contains("Bearer")) {
            fail("溯源字段留下了口令");
        }
        assertEquals("gate", found.request().gateway());
        assertEquals("m--tail", found.request().modelCalls().get(0).model());
    }

    /**
     * 造一条只带一个模型调用和一个工具调用的请求。
     *
     * @param executionId 执行标识
     * @param traceId 追踪标识
     * @param modelCallId 模型调用标识
     * @param toolCallId 工具调用标识
     * @return 可交给记录方法的请求
     */
    private static RequestTraceCommand request(String executionId, String traceId, String modelCallId, String toolCallId) {
        return new RequestTraceCommand(
                traceId,
                executionId,
                "租户甲",
                "user-a",
                "session-a",
                "agent-main@127.0.0.1",
                "ai-gateway",
                List.of(new ModelCallTrace(modelCallId, "deepseek", "deepseek-flash", null, null)),
                List.of(new ToolCallTrace(toolCallId, "clock", null, null)));
    }

    /**
     * 分配一条测试专用的执行标识，并登记到结束后要删除的列表。
     * 前缀 lineage- 加上去掉横线的 UUID，长度仍在 64 以内。
     *
     * @return 新的执行标识
     */
    private String executionId() {
        String executionId = "lineage-" + UUID.randomUUID().toString().replace("-", "");
        executionIds.add(executionId);
        return executionId;
    }

    /**
     * 读取本机口令文件的第一行，只用来断言它没有出现在结果里。
     * 本方法不把口令写进断言消息。
     *
     * @return 口令文本
     */
    private static String password() {
        try {
            Path file = Path.of(System.getProperty("user.home"), ".config", LineageTables.PASSWORD_FILE_NAME);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(0, newline);
            }
            return text.trim();
        } catch (Exception ex) {
            throw new IllegalStateException("没有找到本机 MySQL 口令");
        }
    }

    /**
     * 在限定时间内取回异步结果，并把包装异常还原成原来的运行时异常。
     *
     * @param stage 待等待的异步阶段
     * @param <T> 阶段的结果类型
     * @return 阶段成功时的结果
     */
    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
