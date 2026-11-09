package dev.jasagent.workflow;

import java.util.concurrent.CompletionStage;

/**
 * 在工作流节点的某个阶段调用一个插件。
 */
public interface PluginHost {
    /**
     * 调用一个插件的一个阶段。失败后是中断节点还是记下来继续，由工作流图决定。
     *
     * @param call 执行编号、节点、阶段和插件标识
     * @return 插件完成后的空阶段
     */
    CompletionStage<Void> invoke(PluginCall call);

    /**
     * 一次插件调用要带上的执行、节点、阶段和插件。
     *
     * @param executionId 这次工作流执行的编号
     * @param nodeId 工作流节点标识
     * @param phase 阶段名，例如 beforeModel 或 afterTool
     * @param pluginId 插件标识
     */
    record PluginCall(String executionId, String nodeId, String phase, String pluginId) {
    }
}
