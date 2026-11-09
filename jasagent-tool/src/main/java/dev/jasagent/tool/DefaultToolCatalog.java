package dev.jasagent.tool;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.ArgumentHashes;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolCallEntry;
import dev.jasagent.api.tool.ToolCallStore;
import dev.jasagent.api.tool.ToolCatalog;
import dev.jasagent.api.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * 按名字登记工具。内部工具直接执行；外部工具先申请通行证，待批准的调用先挂起，执行结果写入调用记录。
 */
public final class DefaultToolCatalog implements ToolCatalog {
    private final PassGate gate;
    private final ToolCallStore store;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, ToolCall> suspended = new ConcurrentHashMap<>();

    /**
     * 登记工具清单。同名工具重复出现时立即失败，避免后面调用到不确定的实现。
     *
     * @param gate 申请和拍板通行证的门
     * @param store 保存每次工具调用的记录
     * @param tools 启动时可用的工具，按规格里的名字登记
     */
    public DefaultToolCatalog(PassGate gate, ToolCallStore store, List<Tool> tools) {
        for (Tool tool : tools) {
            String name = tool.spec().name();
            if (this.tools.put(name, tool) != null) {
                throw new AgentException(ErrorCode.TOOL, "duplicate tool " + name);
            }
        }
        this.gate = gate;
        this.store = store;
    }

    /**
     * 按名字调用工具。内部工具和时钟直接执行；外部工具先按参数哈希申请通行证，拒绝或待批时不执行，允许或跳过时才执行。
     *
     * @param call 含用户、执行、目录、工具名和参数的调用
     * @return 完成后给出是否执行、输出和通行证的结果
     */
    @Override
    public CompletionStage<ToolResult> call(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return failed(new AgentException(ErrorCode.TOOL, "unknown tool " + call.name()));
        }
        if (tool.spec().internal() || "clock".equals(call.name())) {
            return tool.call(call).thenCompose(output -> finish(call, null, null, true, output));
        }
        String hash = ArgumentHashes.ofMap(call.arguments());
        PassRequest request = new PassRequest(
                call.userId(),
                call.executionId(),
                call.userDirectory(),
                call.workspaceDirectory(),
                call.name(),
                null,
                hash);
        return gate.acquire(request).thenCompose(pass -> {
            if (pass.decision() == PassDecision.PENDING) {
                suspended.put(pass.passId(), call);
            }
            if (pass.decision() == PassDecision.DENY || pass.decision() == PassDecision.PENDING) {
                return finish(call, hash, pass, false, "");
            }
            return invoke(tool, call, hash, pass);
        });
    }

    /**
     * 对先前挂起的调用做最终决定。拒绝时不执行；允许时取出挂起的原始调用再执行。
     *
     * @param approval 用户对某张通行证的决定
     * @return 完成后给出这次决定对应的工具结果
     */
    @Override
    public CompletionStage<ToolResult> completePending(PassApproval approval) {
        return gate.decide(approval).thenCompose(pass -> {
            ToolCall stored = suspended.remove(pass.passId());
            if (pass.decision() == PassDecision.DENY) {
                ToolCall basis = stored == null
                        ? new ToolCall(pass.userId(), pass.executionId(), approval.userDirectory(), null, pass.toolName(), Map.of())
                        : stored;
                return finish(basis, pass.argumentHash(), pass, false, "");
            }
            if (pass.decision() != PassDecision.ALLOW) {
                return failed(new AgentException(ErrorCode.INTERNAL, "decision"));
            }
            if (stored == null) {
                return failed(new AgentException(ErrorCode.TOOL, "missing suspended call"));
            }
            Tool tool = tools.get(stored.name());
            if (tool == null) {
                return failed(new AgentException(ErrorCode.TOOL, "unknown tool " + stored.name()));
            }
            return invoke(tool, stored, pass.argumentHash(), pass);
        });
    }

    /**
     * 执行已经获准的工具，并按成功或失败记下调用统计。工具自己抛出的异常也记成失败。
     *
     * @param tool 要执行的工具
     * @param call 原始调用
     * @param hash 参数哈希，写入调用记录
     * @param pass 这次执行所依据的通行证
     * @return 完成后给出工具结果；执行失败时阶段以异常结束
     */
    private CompletionStage<ToolResult> invoke(Tool tool, ToolCall call, String hash, Pass pass) {
        CompletionStage<String> stage;
        try {
            stage = tool.call(call);
        } catch (RuntimeException ex) {
            CallStats.load().recordFailure(call.name());
            return failedThrowable(ex);
        }
        return stage.handle((output, error) -> {
            if (error != null) {
                CallStats.load().recordFailure(call.name());
                return failedThrowable(error);
            }
            CallStats.load().recordSuccess(call.name());
            return finish(call, hash, pass, true, output);
        }).thenCompose(future -> future);
    }

    /**
     * 把工具失败收成一个失败阶段。业务异常和运行时异常原样传出，其他异常包成工具错误。
     *
     * @param error 工具调用抛出的异常
     * @return 以对应异常结束的阶段
     */
    private static CompletionStage<ToolResult> failedThrowable(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof AgentException agent) {
            return CompletableFuture.failedFuture(agent);
        }
        if (cause instanceof RuntimeException runtime) {
            return CompletableFuture.failedFuture(runtime);
        }
        return failed(new AgentException(ErrorCode.TOOL, message(cause), null, cause));
    }

    /**
     * 剥掉异步包装，直到露出真正的失败原因。
     *
     * @param error 阶段带回的异常
     * @return 去掉补全包装后的异常
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 取异常说明；没有说明时用异常类的简单名。
     *
     * @param cause 要展示的异常
     * @return 给工具错误用的说明
     */
    private static String message(Throwable cause) {
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * 把是否执行、输出和通行证写成调用记录，并作为工具结果返回。
     *
     * @param call 原始调用
     * @param hash 参数哈希；内部工具没有哈希时为 null
     * @param pass 关联的通行证；内部工具没有通行证时为 null
     * @param executed 工具体是否已经执行
     * @param output 工具输出；未执行时为空串
     * @return 记录写完后的工具结果
     */
    private CompletionStage<ToolResult> finish(ToolCall call, String hash, Pass pass, boolean executed, String output) {
        ToolResult result = new ToolResult(call.executionId(), call.name(), executed, output, pass);
        return store.append(new ToolCallEntry(
                call.executionId(),
                call.userId(),
                call.name(),
                hash,
                pass == null ? null : pass.passId(),
                pass == null ? null : pass.decision(),
                pass == null ? null : pass.reason(),
                executed,
                output)).thenApply(ignored -> result);
    }

    /**
     * 把一条业务异常收成失败阶段。
     *
     * @param <T> 阶段原本要返回的类型
     * @param error 要传出的业务异常
     * @return 以该异常结束的阶段
     */
    private static <T> CompletionStage<T> failed(AgentException error) {
        return CompletableFuture.failedFuture(error);
    }
}
