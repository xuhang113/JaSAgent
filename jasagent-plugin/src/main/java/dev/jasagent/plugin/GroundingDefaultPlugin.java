package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.plugin.AgentPlugin;
import dev.jasagent.api.plugin.PluginCall;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginPhase;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 默认接地插件。
 * 只在模型输出之后调用幻觉检查，其它阶段直接跳过。
 */
public final class GroundingDefaultPlugin implements AgentPlugin {
    /**
     * 返回宿主用来登记和申请通行证的插件标识。
     *
     * @return 固定标识 grounding.default
     */
    @Override
    public String id() {
        return "grounding.default";
    }

    /**
     * 在 afterModel 阶段检查模型输出，其它阶段不调用检查器。
     * 检查器缺失时以插件错误失败；检查通过后输出固定为 grounding.default。
     *
     * @param call 插件调用，用它的阶段、执行标识和模型输出
     * @param context 携带幻觉检查器的上下文，非 afterModel 阶段可以为空
     * @return 跳过时是空串；检查完成后是 grounding.default
     */
    @Override
    public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
        if (call.phase() != PluginPhase.afterModel) {
            return CompletableFuture.completedFuture("");
        }
        if (context == null || context.guard() == null) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.PLUGIN, "hallucination guard missing"));
        }
        return context.guard().check(call.executionId(), call.modelOutput()).thenApply(ignored -> "grounding.default");
    }
}
