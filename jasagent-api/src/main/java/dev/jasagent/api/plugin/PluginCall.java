package dev.jasagent.api.plugin;

import java.nio.file.Path;

/**
 * 宿主执行一个插件时使用的调用单。
 * 用户目录和工作区目录交给通行证判断范围，失败策略决定插件体抛错后调用是否中止。
 *
 * @param userId 发起调用的用户
 * @param executionId 所属执行
 * @param userDirectory 该用户的目录，通行证和落盘按它定位
 * @param workspaceDirectory 工作区目录
 * @param pluginId 要调用的插件标识
 * @param phase 当前时机；恢复挂起时若没有原调用可以空着
 * @param arguments 参与通行证哈希的参数文本
 * @param modelOutput 模型输出，供模型返回之后的守卫使用
 * @param failure 插件体抛错时继续返回，还是让调用失败
 */
public record PluginCall(
        String userId,
        String executionId,
        Path userDirectory,
        Path workspaceDirectory,
        String pluginId,
        PluginPhase phase,
        String arguments,
        String modelOutput,
        PluginFailure failure) {
}
