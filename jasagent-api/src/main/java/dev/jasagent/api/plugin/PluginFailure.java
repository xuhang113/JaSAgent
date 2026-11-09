package dev.jasagent.api.plugin;

/**
 * 插件体抛错之后，这次调用如何收场。
 */
public enum PluginFailure {
    /**
     * 记下异常后正常返回。结果里失败为真、已执行为假。
     */
    CONTINUE,
    /**
     * 让这次调用以插件错误失败，不再当作正常结果交回去。
     */
    FAIL
}
