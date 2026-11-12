package dev.jasagent.trace.lineage;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 用户请求溯源和引用溯源的对外调用点。
 * 网关或组装层在请求进入并知道租户、用户、会话和 agent 后调用 {@link #recordRequest}。
 * 回答引用检索片段时调用 {@link #recordCitation}；示例 RAG 的命中调用 {@link #recordRagHits}。
 * 之后任何目录用 {@link #findByExecutionId} 按执行标识回查，不必改这里的表。
 * 本类不改工作流怎么走、不改登录口令、不改 LangChain4j 路由，也不改进程内通行证。
 * 阻塞的数据库调用放在虚拟线程上，方法返回尚未完成的阶段。
 * 实例不保存口令，可以共用。口令只在每次调用时从用户主目录的口令文件读取。
 */
public final class RequestLineage {
    /**
     * 实际执行语句的库访问。每个调用点共用这一份，它自己不保存连接。
     */
    private final LineageDatabase database = new LineageDatabase();

    /**
     * 构造一份不带连接的调用点。
     * 第一次写入时才连接 jasagent，并在库里补上还没有的溯源表。
     */
    public RequestLineage() {
    }

    /**
     * 记下一次从网关进入的用户请求。
     * traceId 没传或只有空白时生成 32 位十六进制，并写到这次带上的每条模型调用和工具调用。
     * 同一个 executionId 再调用会替换请求身份，以及模型调用和工具调用列表；已经记下的引用还在，空着的 traceId 会补成这次的值。
     * 字段里的 Authorization、Bearer 和库口令会被去掉。去掉后变成空白则这次调用失败，错误码是 {@code INTERNAL}，消息不带回原值。
     *
     * @param command 租户、用户、会话、agent、网关，以及这次的模型调用和工具调用
     * @return 落库后的请求，traceId 已经生成或沿用调用方传入的值
     */
    public CompletionStage<RequestTrace> recordRequest(RequestTraceCommand command) {
        return VirtualIo.supply(() -> {
            RequestTrace request = prepareRequest(command);
            return database.saveRequest(readPassword(), request);
        });
    }

    /**
     * 记下模型回答引用的一个检索片段。
     * 服务、文档或片段标识、会话和 executionId 都会入库。traceId 没传时，这次执行已经有请求就抄请求上的；还没有就先写空串。
     * 同一执行、同一回答、同一服务、同一文档和同一片段再记一次，返回原来的引用行，不另起一行。
     *
     * @param command 引用的执行、会话、回答、服务、文档和片段
     * @return 落库后的引用，能用 answerId 追到这次回答
     */
    public CompletionStage<CitationTrace> recordCitation(CitationTraceCommand command) {
        return VirtualIo.supply(() -> {
            CitationTrace citation = prepareCitation(command);
            return database.saveCitations(readPassword(), List.of(citation)).get(0);
        });
    }

    /**
     * 把示例 RAG 返回的命中记成这次回答的引用。
     * 每条命中的 documentId 对应 RAG 的 docId，fragmentId 对应 chunkId。service 空白时写成 {@link LineageTables#SERVICE_RAG}。
     * 没有命中时不连接数据库，直接返回空列表。命中列表为 null，或其中某一条为 null 时失败，错误码是 {@code INTERNAL}。
     * 记完后用 {@link #findByExecutionId} 可以按 executionId 看到这些命中挂在 answerId 上。
     *
     * @param executionId 这次回答所属的执行
     * @param sessionId 会话
     * @param answerId 引用这些命中的回答
     * @param hits 检索返回的命中；没有命中时传空列表
     * @return 与命中顺序一致的引用
     */
    public CompletionStage<List<CitationTrace>> recordRagHits(
            String executionId,
            String sessionId,
            String answerId,
            List<RagHitCitation> hits) {
        return VirtualIo.supply(() -> {
            List<CitationTrace> citations = prepareHits(executionId, sessionId, answerId, hits);
            if (citations.isEmpty()) {
                return List.of();
            }
            return database.saveCitations(readPassword(), citations);
        });
    }

    /**
     * 按 executionId 查回这次请求打到的租户、用户、会话和 agent，以及模型调用、工具调用和引用。
     * 没记过请求时 {@link ExecutionLineage#request()} 为 null，{@link ExecutionLineage#recorded()} 为假。
     * 只记过引用时，引用仍在 {@link ExecutionLineage#citations()} 里。没有这行执行时，引用列表为空。
     *
     * @param executionId 要回查的执行标识，不能为空
     * @return 这次执行的溯源
     */
    public CompletionStage<ExecutionLineage> findByExecutionId(String executionId) {
        return VirtualIo.supply(() -> {
            String id = LineageText.required(executionId, "executionId", LineageTables.EXECUTION_ID_MAX);
            return database.find(readPassword(), id);
        });
    }

    /**
     * 返回可以写进日志的连接说明。
     * 只有库地址和账号，没有口令，也没有口令文件的内容。
     *
     * @return 主机、库名和账号
     */
    public String endpointDescription() {
        return LineageTables.JDBC_URL + " user=" + LineageTables.USER;
    }

    /**
     * 删掉一次执行的溯源行。
     * 只给同包测试在结束后清掉自己写入的数据，其它目录不要调用。
     *
     * @param executionId 测试用的执行标识；空白时什么都不做
     */
    void deleteExecution(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        database.deleteExecution(readPassword(), executionId.trim());
    }

    /**
     * 检查请求字段，生成或沿用 traceId，并让每条调用都带上这个 traceId。
     * 口令在结构检查通过之后才读，避免空参数也去碰口令文件。
     *
     * @param command 调用方传入的请求；为 null 时失败
     * @return 可以交给数据库的请求
     */
    private RequestTrace prepareRequest(RequestTraceCommand command) {
        if (command == null) {
            throw new AgentException(ErrorCode.INTERNAL, "请求不能为空");
        }
        String executionId = LineageText.required(command.executionId(), "executionId", LineageTables.EXECUTION_ID_MAX);
        LineageText.required(command.tenantId(), "tenantId", LineageTables.TENANT_ID_MAX);
        LineageText.required(command.userId(), "userId", LineageTables.USER_ID_MAX);
        LineageText.required(command.sessionId(), "sessionId", LineageTables.SESSION_ID_MAX);
        LineageText.required(command.agentId(), "agentId", LineageTables.AGENT_ID_MAX);
        LineageText.required(command.gateway(), "gateway", LineageTables.GATEWAY_MAX);
        List<ModelCallTrace> models = copyModels(command.modelCalls());
        List<ToolCallTrace> tools = copyTools(command.toolCalls());
        String password = readPassword();
        String traceId = resolveTraceId(command.traceId(), password);
        return new RequestTrace(
                traceId,
                executionId,
                LineageText.requiredClean(command.tenantId(), "tenantId", LineageTables.TENANT_ID_MAX, password),
                LineageText.requiredClean(command.userId(), "userId", LineageTables.USER_ID_MAX, password),
                LineageText.requiredClean(command.sessionId(), "sessionId", LineageTables.SESSION_ID_MAX, password),
                LineageText.requiredClean(command.agentId(), "agentId", LineageTables.AGENT_ID_MAX, password),
                LineageText.requiredClean(command.gateway(), "gateway", LineageTables.GATEWAY_MAX, password),
                cleanModels(models, password, executionId, traceId),
                cleanTools(tools, password, executionId, traceId));
    }

    /**
     * 检查一条引用并去掉凭据。
     * traceId 没传时留空，交给数据库去抄请求行。
     *
     * @param command 调用方传入的引用；为 null 时失败
     * @return 可以交给数据库的引用，citationId 先空着
     */
    private CitationTrace prepareCitation(CitationTraceCommand command) {
        if (command == null) {
            throw new AgentException(ErrorCode.INTERNAL, "引用不能为空");
        }
        String executionId = LineageText.required(command.executionId(), "executionId", LineageTables.EXECUTION_ID_MAX);
        LineageText.required(command.sessionId(), "sessionId", LineageTables.SESSION_ID_MAX);
        LineageText.required(command.answerId(), "answerId", LineageTables.ANSWER_ID_MAX);
        LineageText.required(command.service(), "service", LineageTables.SERVICE_MAX);
        LineageText.required(command.documentId(), "documentId", LineageTables.DOCUMENT_ID_MAX);
        LineageText.required(command.fragmentId(), "fragmentId", LineageTables.FRAGMENT_ID_MAX);
        String password = readPassword();
        String traceId = "";
        if (command.traceId() != null && !command.traceId().isBlank()) {
            traceId = resolveTraceId(command.traceId(), password);
        }
        return new CitationTrace(
                "",
                executionId,
                traceId,
                LineageText.requiredClean(command.sessionId(), "sessionId", LineageTables.SESSION_ID_MAX, password),
                LineageText.requiredClean(command.answerId(), "answerId", LineageTables.ANSWER_ID_MAX, password),
                LineageText.requiredClean(command.service(), "service", LineageTables.SERVICE_MAX, password),
                LineageText.requiredClean(command.documentId(), "documentId", LineageTables.DOCUMENT_ID_MAX, password),
                LineageText.requiredClean(command.fragmentId(), "fragmentId", LineageTables.FRAGMENT_ID_MAX, password));
    }

    /**
     * 把 RAG 命中收成引用。
     * 服务名空白时写成 rag。列表为空时返回空列表，不读口令。
     *
     * @param executionId 执行标识
     * @param sessionId 会话
     * @param answerId 回答标识
     * @param hits 检索命中
     * @return 可以交给数据库的引用
     */
    private List<CitationTrace> prepareHits(String executionId, String sessionId, String answerId, List<RagHitCitation> hits) {
        if (hits == null) {
            throw new AgentException(ErrorCode.INTERNAL, "检索命中不能为空");
        }
        if (hits.isEmpty()) {
            LineageText.required(executionId, "executionId", LineageTables.EXECUTION_ID_MAX);
            LineageText.required(sessionId, "sessionId", LineageTables.SESSION_ID_MAX);
            LineageText.required(answerId, "answerId", LineageTables.ANSWER_ID_MAX);
            return List.of();
        }
        List<CitationTrace> citations = new ArrayList<>();
        for (RagHitCitation hit : hits) {
            if (hit == null) {
                throw new AgentException(ErrorCode.INTERNAL, "检索命中不能为空");
            }
            String service = hit.service();
            if (service == null || service.isBlank()) {
                service = LineageTables.SERVICE_RAG;
            }
            citations.add(prepareCitation(new CitationTraceCommand(
                    executionId,
                    sessionId,
                    answerId,
                    service,
                    hit.documentId(),
                    hit.fragmentId(),
                    null)));
        }
        return List.copyOf(citations);
    }

    /**
     * 复制模型调用列表，并拒绝 null 元素。
     * 这里还不读口令，只检查列表形状。
     *
     * @param calls 调用方传入的列表，可以为 null
     * @return 不含 null 的列表
     */
    private static List<ModelCallTrace> copyModels(List<ModelCallTrace> calls) {
        if (calls == null) {
            return List.of();
        }
        List<ModelCallTrace> copied = new ArrayList<>();
        for (ModelCallTrace call : calls) {
            if (call == null) {
                throw new AgentException(ErrorCode.INTERNAL, "模型调用不能为空");
            }
            copied.add(call);
        }
        return copied;
    }

    /**
     * 复制工具调用列表，并拒绝 null 元素。
     *
     * @param calls 调用方传入的列表，可以为 null
     * @return 不含 null 的列表
     */
    private static List<ToolCallTrace> copyTools(List<ToolCallTrace> calls) {
        if (calls == null) {
            return List.of();
        }
        List<ToolCallTrace> copied = new ArrayList<>();
        for (ToolCallTrace call : calls) {
            if (call == null) {
                throw new AgentException(ErrorCode.INTERNAL, "工具调用不能为空");
            }
            copied.add(call);
        }
        return copied;
    }

    /**
     * 去掉模型调用里的凭据，并绑上这次请求的 traceId 和 executionId。
     * 同一次请求里调用标识重复时失败。
     *
     * @param calls 已经排除 null 元素的调用
     * @param password 本机库口令，只用于从字段里删掉它
     * @param executionId 这次执行
     * @param traceId 这次请求的追踪标识
     * @return 可以入库的模型调用
     */
    private static List<ModelCallTrace> cleanModels(List<ModelCallTrace> calls, String password, String executionId, String traceId) {
        List<ModelCallTrace> cleaned = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ModelCallTrace call : calls) {
            String callId = LineageText.requiredClean(call.callId(), "callId", LineageTables.CALL_ID_MAX, password);
            if (!ids.add(callId)) {
                throw new AgentException(ErrorCode.INTERNAL, "模型调用标识重复");
            }
            cleaned.add(new ModelCallTrace(
                    callId,
                    LineageText.requiredClean(call.provider(), "provider", LineageTables.PROVIDER_MAX, password),
                    LineageText.requiredClean(call.model(), "model", LineageTables.MODEL_MAX, password),
                    traceId,
                    executionId));
        }
        return List.copyOf(cleaned);
    }

    /**
     * 去掉工具调用里的凭据，并绑上这次请求的 traceId 和 executionId。
     * 同一次请求里调用标识重复时失败。
     *
     * @param calls 已经排除 null 元素的调用
     * @param password 本机库口令，只用于从字段里删掉它
     * @param executionId 这次执行
     * @param traceId 这次请求的追踪标识
     * @return 可以入库的工具调用
     */
    private static List<ToolCallTrace> cleanTools(List<ToolCallTrace> calls, String password, String executionId, String traceId) {
        List<ToolCallTrace> cleaned = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ToolCallTrace call : calls) {
            String callId = LineageText.requiredClean(call.callId(), "callId", LineageTables.CALL_ID_MAX, password);
            if (!ids.add(callId)) {
                throw new AgentException(ErrorCode.INTERNAL, "工具调用标识重复");
            }
            cleaned.add(new ToolCallTrace(
                    callId,
                    LineageText.requiredClean(call.toolName(), "toolName", LineageTables.TOOL_NAME_MAX, password),
                    traceId,
                    executionId));
        }
        return List.copyOf(cleaned);
    }

    /**
     * 沿用调用方传入的 traceId；没传时生成 32 位十六进制。
     * 传入的值如果去掉密钥后变成空白，这次失败，不拿密钥去生成或入库。
     *
     * @param supplied 调用方传入的追踪标识，可以为 null
     * @param password 本机库口令
     * @return 要写入请求行的追踪标识
     */
    private static String resolveTraceId(String supplied, String password) {
        if (supplied == null || supplied.isBlank()) {
            String raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            return raw.substring(0, LineageTables.GENERATED_TRACE_ID_LENGTH);
        }
        String cleaned = LineageText.redact(LineageText.required(supplied, "traceId", LineageTables.TRACE_ID_MAX), password);
        if (cleaned.isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, "traceId 含有不能入库的密钥");
        }
        return cleaned;
    }

    /**
     * 读取用户主目录 {@code .config/urag-mysql-root.password} 的第一行。
     * 文件没有、读失败或第一行是空白时抛出持久化错误。异常消息和原因里都不放文件内容。
     *
     * @return 去掉首尾空白后的口令
     */
    private static String readPassword() {
        Path file = Path.of(System.getProperty("user.home", ""), ".config", LineageTables.PASSWORD_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(0, newline);
            }
            if (text.endsWith("\r")) {
                text = text.substring(0, text.length() - 1);
            }
            text = text.trim();
            if (text.isEmpty()) {
                throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
            }
            return text;
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "没有找到本机 MySQL 口令");
        }
    }
}
