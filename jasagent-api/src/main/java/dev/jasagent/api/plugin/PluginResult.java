package dev.jasagent.api.plugin;

import dev.jasagent.api.pass.Pass;

/**
 * 插件宿主对一次调用给出的结果，并带上当时的通行证。
 * 插件体没跑时 {@code executed} 为假；插件体抛错但调用仍正常返回时 {@code failed} 为真。
 *
 * @param executionId 这次调用所属的执行
 * @param pluginId 被调用的插件标识
 * @param executed 插件体是否已经执行
 * @param failed 插件体是否抛错
 * @param output 插件体返回的文本；未执行时为空串，抛错后留下错误说明
 * @param pass 这次调用拿到的通行证，里面有决定和原因
 */
public record PluginResult(
        String executionId,
        String pluginId,
        boolean executed,
        boolean failed,
        String output,
        Pass pass) {
}
