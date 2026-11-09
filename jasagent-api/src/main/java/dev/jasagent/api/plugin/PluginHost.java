package dev.jasagent.api.plugin;

import dev.jasagent.api.pass.PassApproval;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 装载并调用智能体插件。
 * 每次调用先申请通行证：允许或跳过才进入插件体，拒绝或挂起只记结果。
 */
public interface PluginHost {
    /**
     * 列出当前已经装载的插件标识，供调用方按标识点名。
     *
     * @return 已装载的插件标识
     */
    List<String> ids();

    /**
     * 按通行证决定执行插件，并把这次决定追加到调用记录。
     * 插件体抛错时，调用单的失败策略若是 {@link PluginFailure#FAIL}，阶段以插件错误结束；否则记下失败后正常返回结果。
     *
     * @param call 用户、目录、插件、时机、参数和失败策略
     * @param context 传给插件体的现场，需要落地核对时从这里取守卫
     * @return 完成后得到调用结果的阶段；插件不存在时阶段以插件错误结束
     */
    CompletionStage<PluginResult> invoke(PluginCall call, PluginContext context);

    /**
     * 按审批结果把先前挂起的调用做完。
     * 决定为拒绝时不执行插件体；决定为允许时取出挂起时保存的调用再执行。找不到当时的调用则阶段以插件错误结束。
     *
     * @param approval 审批人、执行、通行证标识和决定
     * @param context 恢复执行时传给插件体的现场
     * @return 完成后得到调用结果的阶段
     */
    CompletionStage<PluginResult> completePending(PassApproval approval, PluginContext context);
}
