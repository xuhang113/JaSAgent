package dev.jasagent.api.plugin;

/**
 * 插件调用单上的时机。
 * 宿主用它和参数、模型输出一起计算通行证哈希，插件也用它判断这一拍要不要做事。
 */
public enum PluginPhase {
    /**
     * 刚进入节点。同一节点只经过这一轮。
     */
    onNodeStart,
    /**
     * 向模型发请求之前。
     */
    beforeModel,
    /**
     * 模型已经返回。落地核对在这一时机看模型输出。
     */
    afterModel,
    /**
     * 模型给出的工具调用即将执行。
     */
    beforeTool,
    /**
     * 工具调用已经返回。
     */
    afterTool,
    /**
     * 即将离开该节点。
     */
    onNodeEnd
}
