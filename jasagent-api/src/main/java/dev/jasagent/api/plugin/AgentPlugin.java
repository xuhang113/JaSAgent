package dev.jasagent.api.plugin;

import java.util.concurrent.CompletionStage;

/**
 * 可被插件宿主装载并执行的插件体。
 * 宿主只在通行证为允许或跳过时调用它，并把返回文本记成这次调用的输出。
 */
public interface AgentPlugin {
    /**
     * 插件标识。宿主拒绝两份实现共用同一个标识。
     *
     * @return 插件标识
     */
    String id();

    /**
     * 执行插件体，并返回要记入结果的文本。
     * 抛错后的收场由调用单上的失败策略决定，插件体自己不改统计次数。
     *
     * @param call 时机、参数、模型输出和失败策略
     * @param context 节点现场；需要守卫时从这里取
     * @return 完成后得到插件输出文本的阶段
     */
    CompletionStage<String> invoke(PluginCall call, PluginContext context);
}
