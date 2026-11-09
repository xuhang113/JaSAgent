package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.ArgumentHashes;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.plugin.PluginTool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * 插件工具目录。
 * 按工具名索引，调用前申请通行证；拒绝和待审批不执行工具，允许和跳过才执行，并把成败记入调用统计。
 */
public final class PluginToolCatalog {
    private final PassGate gate;
    private final Map<String, PluginTool> tools;

    /**
     * 按工具规格里的名字登记工具。
     * 同名工具出现两次时以工具错误失败。
     *
     * @param gate 申请通行证的门
     * @param tools 要登记的工具
     */
    public PluginToolCatalog(PassGate gate, List<PluginTool> tools) {
        Map<String, PluginTool> named = new LinkedHashMap<>();
        for (PluginTool tool : tools) {
            String name = tool.spec().name();
            if (named.put(name, tool) != null) {
                throw new AgentException(ErrorCode.TOOL, "duplicate tool " + name);
            }
        }
        this.gate = gate;
        this.tools = named;
    }

    /**
     * 用工具接口的类加载器找出已注册工具，再装进目录。
     *
     * @param gate 申请通行证的门
     * @return 装有 ServiceLoader 所找到工具的目录
     */
    public static PluginToolCatalog load(PassGate gate) {
        List<PluginTool> found = new ArrayList<>();
        ServiceLoader.load(PluginTool.class, PluginTool.class.getClassLoader()).forEach(found::add);
        return new PluginToolCatalog(gate, found);
    }

    /**
     * 返回已登记的工具名。
     *
     * @return 工具名副本，调用方改这个列表不影响目录
     */
    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 按名字取出工具。
     *
     * @param name 工具名
     * @return 已登记的工具；没有这个名字时抛出工具错误
     */
    public PluginTool require(String name) {
        PluginTool tool = tools.get(name);
        if (tool == null) {
            throw new AgentException(ErrorCode.TOOL, "unknown tool " + name);
        }
        return tool;
    }

    /**
     * 先按参数计算哈希并申请通行证，再决定是否执行工具。
     * 拒绝和待审批返回未执行的空输出。允许和跳过才调用工具。其它决定以内部错误失败。
     *
     * @param call 含工具名、参数和目录的调用
     * @return 工具结果；未知工具时阶段直接失败
     */
    public CompletionStage<ToolResult> call(ToolCall call) {
        PluginTool tool = tools.get(call.name());
        if (tool == null) {
            return failed(new AgentException(ErrorCode.TOOL, "unknown tool " + call.name()));
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
            if (pass.decision() == PassDecision.DENY || pass.decision() == PassDecision.PENDING) {
                return CompletableFuture.completedFuture(new ToolResult(call.executionId(), call.name(), false, "", pass));
            }
            if (pass.decision() != PassDecision.ALLOW && pass.decision() != PassDecision.SKIPPED) {
                return failed(new AgentException(ErrorCode.INTERNAL, "decision"));
            }
            return invoke(tool, call, pass);
        });
    }

    /**
     * 执行工具，并把同步或异步失败记入调用统计。
     * 成功时统计成功次数，输出为空引用时写成空串。
     *
     * @param tool 已经通过通行证的工具
     * @param call 原始调用
     * @param pass 这次调用拿到的通行证，放进结果
     * @return 已执行的工具结果；工具失败时阶段以原异常或工具错误结束
     */
    private CompletionStage<ToolResult> invoke(PluginTool tool, ToolCall call, Pass pass) {
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
            String text = output == null ? "" : output;
            return CompletableFuture.completedFuture(new ToolResult(call.executionId(), call.name(), true, text, pass));
        }).thenCompose(future -> future);
    }

    /**
     * 把工具失败收成失败阶段。
     * 代理异常和运行时异常原样传出，其它受检异常包成工具错误。
     *
     * @param error 工具抛出或阶段带出的异常
     * @return 立即失败的工具结果阶段
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
     * 剥掉异步包装，找到工具真正抛出的原因。
     *
     * @param error 阶段失败时拿到的异常
     * @return 最内层的原因
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 取出能放进工具错误的说明。
     *
     * @param cause 失败原因
     * @return 异常消息；没有消息时用异常类的简单名
     */
    private static String message(Throwable cause) {
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * 做一个已经失败的工具结果阶段。
     *
     * @param error 要传出去的异常
     * @return 立即以该异常结束的阶段
     */
    private static CompletionStage<ToolResult> failed(AgentException error) {
        return CompletableFuture.failedFuture(error);
    }
}
