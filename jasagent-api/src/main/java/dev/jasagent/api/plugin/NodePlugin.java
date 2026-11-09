package dev.jasagent.api.plugin;

import java.util.concurrent.CompletionStage;

/**
 * 挂在工作流节点上、只接收节点现场的插件。
 * 它不返回文本，执行完就用阶段结束来表示。
 */
public interface NodePlugin {
    /**
     * 这个插件的标识。宿主用它点名，并拒绝重复标识。
     *
     * @return 插件标识
     */
    String id();

    /**
     * 在给定的节点现场上执行插件。
     * 插件做的检查、拦截或外部动作都发生在阶段完成之前。
     *
     * @param context 节点、执行、载荷和守卫
     * @return 插件做完后结束的阶段
     */
    CompletionStage<Void> invoke(PluginContext context);
}
