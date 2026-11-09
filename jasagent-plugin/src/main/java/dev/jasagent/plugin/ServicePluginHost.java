package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.ArgumentHashes;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.AgentPlugin;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.plugin.PluginCall;
import dev.jasagent.api.plugin.PluginCallEntry;
import dev.jasagent.api.plugin.PluginCallStore;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginFailure;
import dev.jasagent.api.plugin.PluginHost;
import dev.jasagent.api.plugin.PluginResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * 插件宿主。
 * 调用前先申请通行证，拒绝和待审批都不进入插件；允许之后才执行，并把每一次决定写进调用记录。
 */
public final class ServicePluginHost implements PluginHost {
    private final PassGate gate;
    private final PluginCallStore store;
    private final Map<String, AgentPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, PluginCall> suspended = new ConcurrentHashMap<>();

    /**
     * 按插件标识登记宿主要管理的插件。
     * 同一个标识出现两次时以插件错误失败，宿主不会建出来。
     *
     * @param gate 申请和裁决通行证的门
     * @param store 追加插件调用记录的存储
     * @param plugins 要登记的插件，按标识索引
     */
    public ServicePluginHost(PassGate gate, PluginCallStore store, List<AgentPlugin> plugins) {
        for (AgentPlugin plugin : plugins) {
            if (this.plugins.put(plugin.id(), plugin) != null) {
                throw new AgentException(ErrorCode.PLUGIN, "duplicate plugin " + plugin.id());
            }
        }
        this.gate = gate;
        this.store = store;
    }

    /**
     * 用插件接口的类加载器找出已注册的插件，再装进一个宿主。
     *
     * @param gate 申请通行证的门
     * @param store 调用记录存储
     * @return 装有 ServiceLoader 所找到插件的宿主
     */
    public static ServicePluginHost load(PassGate gate, PluginCallStore store) {
        List<AgentPlugin> found = new ArrayList<>();
        ServiceLoader.load(AgentPlugin.class, AgentPlugin.class.getClassLoader()).forEach(found::add);
        return new ServicePluginHost(gate, store, found);
    }

    /**
     * 返回已经登记的插件标识。
     *
     * @return 标识副本，调用方改这个列表不影响宿主
     */
    @Override
    public List<String> ids() {
        return List.copyOf(plugins.keySet());
    }

    /**
     * 先按阶段、参数和模型输出计算哈希并申请通行证，再决定是否执行插件。
     * 待审批的调用按通行证编号挂起。拒绝和待审批只记结果，不进入插件。
     *
     * @param call 要执行的插件调用，含用户、执行、阶段和失败策略
     * @param context 插件可见的上下文，接地插件用它做幻觉检查
     * @return 执行或被拦住后的插件结果；未知插件时阶段直接失败
     */
    @Override
    public CompletionStage<PluginResult> invoke(PluginCall call, PluginContext context) {
        AgentPlugin plugin = plugins.get(call.pluginId());
        if (plugin == null) {
            return failed(new AgentException(ErrorCode.PLUGIN, "unknown plugin " + call.pluginId()));
        }
        String hash = ArgumentHashes.ofPlugin(call.phase().name(), call.arguments(), call.modelOutput());
        PassRequest request = new PassRequest(
                call.userId(),
                call.executionId(),
                call.userDirectory(),
                call.workspaceDirectory(),
                null,
                call.pluginId(),
                hash);
        return gate.acquire(request).thenCompose(pass -> {
            if (pass.decision() == PassDecision.PENDING) {
                suspended.put(pass.passId(), call);
            }
            if (pass.decision() == PassDecision.DENY || pass.decision() == PassDecision.PENDING) {
                return finish(call, hash, pass, false, false, "");
            }
            return run(plugin, call, context, pass, hash);
        });
    }

    /**
     * 用审批结果结束一次挂起的插件调用。
     * 拒绝时只记未执行；允许时取出挂起的原始调用并执行。找不到挂起调用或插件时失败。
     *
     * @param approval 用户对某张通行证的允许或拒绝
     * @param context 恢复执行时交给插件的上下文
     * @return 补记或执行后的插件结果
     */
    @Override
    public CompletionStage<PluginResult> completePending(PassApproval approval, PluginContext context) {
        return gate.decide(approval).thenCompose(pass -> {
            PluginCall stored = suspended.remove(pass.passId());
            if (pass.decision() == PassDecision.DENY) {
                PluginCall basis = stored == null
                        ? new PluginCall(pass.userId(), pass.executionId(), approval.userDirectory(), null, pass.pluginId(), null, "", "", null)
                        : stored;
                return finish(basis, pass.argumentHash(), pass, false, false, "");
            }
            if (pass.decision() != PassDecision.ALLOW) {
                return failed(new AgentException(ErrorCode.INTERNAL, "decision"));
            }
            if (stored == null) {
                return failed(new AgentException(ErrorCode.PLUGIN, "missing suspended call"));
            }
            AgentPlugin plugin = plugins.get(stored.pluginId());
            if (plugin == null) {
                return failed(new AgentException(ErrorCode.PLUGIN, "unknown plugin " + stored.pluginId()));
            }
            return run(plugin, stored, context, pass, pass.argumentHash());
        });
    }

    /**
     * 执行插件，并把成功或失败记到调用统计和调用记录。
     * 失败策略是 FAIL 时，先把失败记录写完，再让阶段以插件错误结束；否则只返回失败结果。
     *
     * @param plugin 已经通过通行证的插件
     * @param call 原始调用
     * @param context 交给插件的上下文
     * @param pass 这次调用拿到的通行证
     * @param hash 参数哈希，写进调用记录
     * @return 执行结果；失败策略要求中断时，阶段以插件异常结束
     */
    private CompletionStage<PluginResult> run(AgentPlugin plugin, PluginCall call, PluginContext context, Pass pass, String hash) {
        return plugin.invoke(call, context).handle((output, error) -> {
            if (error == null) {
                CallStats.load().recordSuccess(call.pluginId());
                return finish(call, hash, pass, true, false, output == null ? "" : output);
            }
            CallStats.load().recordFailure(call.pluginId());
            Throwable cause = unwrap(error);
            if (call.failure() == PluginFailure.FAIL) {
                return finish(call, hash, pass, false, true, message(cause)).thenCompose(ignored -> {
                    CompletableFuture<PluginResult> failed = new CompletableFuture<>();
                    failed.completeExceptionally(asPlugin(cause));
                    return failed;
                });
            }
            return finish(call, hash, pass, false, true, message(cause));
        }).thenCompose(stage -> stage);
    }

    /**
     * 组装插件结果，并把这一次决定追加到调用记录。
     * 阶段为空时记录里的阶段名写成空串。
     *
     * @param call 本次插件调用
     * @param hash 参数哈希
     * @param pass 通行证，还没有时可以为空
     * @param executed 插件体是否已经执行
     * @param failed 插件是否以失败结束
     * @param output 插件输出；未执行或失败时通常是空串或错误说明
     * @return 写完记录后的插件结果
     */
    private CompletionStage<PluginResult> finish(
            PluginCall call,
            String hash,
            Pass pass,
            boolean executed,
            boolean failed,
            String output) {
        PluginResult result = new PluginResult(call.executionId(), call.pluginId(), executed, failed, output, pass);
        return store.append(new PluginCallEntry(
                call.executionId(),
                call.userId(),
                call.pluginId(),
                call.phase() == null ? "" : call.phase().name(),
                hash,
                pass == null ? null : pass.passId(),
                pass == null ? null : pass.decision(),
                pass == null ? null : pass.reason(),
                executed,
                failed,
                output)).thenApply(ignored -> result);
    }

    /**
     * 把插件抛出的原因收成插件错误。
     * 已经是插件错误码的异常保持原样，避免再包一层。
     *
     * @param cause 插件或下层抛出的原因
     * @return 插件错误码的异常
     */
    private static AgentException asPlugin(Throwable cause) {
        if (cause instanceof AgentException agent && agent.code() == ErrorCode.PLUGIN) {
            return agent;
        }
        return new AgentException(ErrorCode.PLUGIN, message(cause), null, cause);
    }

    /**
     * 取出能写进记录的错误说明。
     *
     * @param cause 失败原因
     * @return 异常消息；没有消息时用异常类的简单名
     */
    private static String message(Throwable cause) {
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * 剥掉异步包装，找到插件真正抛出的原因。
     * 只解开完成异常和执行异常，并且停在没有更深层原因的那一层。
     *
     * @param error 阶段失败时拿到的异常
     * @return 最内层的业务原因
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 做一个已经失败的阶段，让调用方走统一的异常路径。
     *
     * @param <T> 这个阶段本来要产生的结果类型
     * @param error 要传出去的异常
     * @return 立即以该异常结束的阶段
     */
    private static <T> CompletionStage<T> failed(AgentException error) {
        return CompletableFuture.failedFuture(error);
    }
}
