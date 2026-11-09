package dev.jasagent.api.plugin;

import dev.jasagent.api.dsl.PluginPhase;

/**
 * 一次插件调用能看到的节点现场和幻觉守卫。
 * 只准备了守卫时，插件、时机、执行、节点和载荷都可以空着。
 *
 * @param pluginId 插件标识，未知时为空
 * @param phase 工作流 DSL 里的时机，未知时为空
 * @param executionId 所属执行，未知时为空
 * @param nodeId 当前节点标识，未知时为空
 * @param payload 交给插件的载荷文本，未知时为空
 * @param guard 用来核对模型输出的守卫，没有时为空
 */
public record PluginContext(
        String pluginId,
        PluginPhase phase,
        String executionId,
        String nodeId,
        String payload,
        HallucinationGuard guard) {
    /**
     * 只放进幻觉守卫，其余字段都空着。
     * 落地插件在还没有节点字段时用这份现场取守卫。
     *
     * @param guard 要放进现场的幻觉守卫
     */
    public PluginContext(HallucinationGuard guard) {
        this(null, null, null, null, null, guard);
    }

    /**
     * 放进插件和节点字段，不附带守卫。
     *
     * @param pluginId 插件标识
     * @param phase 这次调用的时机
     * @param executionId 所属执行
     * @param nodeId 当前节点
     * @param payload 交给插件的载荷
     */
    public PluginContext(String pluginId, PluginPhase phase, String executionId, String nodeId, String payload) {
        this(pluginId, phase, executionId, nodeId, payload, null);
    }
}
