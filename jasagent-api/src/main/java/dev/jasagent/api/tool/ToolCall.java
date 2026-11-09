package dev.jasagent.api.tool;

import java.nio.file.Path;
import java.util.Map;

/**
 * 目录执行一个工具时的完整调用。参数用于计算通行证摘要，两个目录告诉工具能读用户数据还是工作区数据。
 *
 * @param userId 发起调用的用户登录名
 * @param executionId 调用所属的执行编号
 * @param userDirectory 该用户的 jasagent 目录；旧调用形状里为 null
 * @param workspaceDirectory 工作区里的 jasagent 目录；未知时为 null
 * @param name 工具名
 * @param arguments 字符串参数表；缺失时为空映射
 */
public record ToolCall(
        String userId,
        String executionId,
        Path userDirectory,
        Path workspaceDirectory,
        String name,
        Map<String, String> arguments) {
    /**
     * 复制参数表。arguments 为 null 时改成空映射，否则复制为不可变映射。
     *
     * @param userId 发起调用的用户登录名
     * @param executionId 调用所属的执行编号
     * @param userDirectory 该用户的 jasagent 目录
     * @param workspaceDirectory 工作区里的 jasagent 目录
     * @param name 工具名
     * @param arguments 字符串参数表，null 会变成空映射
     */
    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /**
     * 按旧的调用形状保留用户、执行和工具名。调用编号和参数 JSON 不写入记录，两个目录留空，参数表为空。
     *
     * @param id 旧调用形状里的调用编号，记录中不保留
     * @param name 工具名
     * @param argumentsJson 旧调用形状里的参数 JSON，记录中不保留
     * @param userId 发起调用的用户登录名
     * @param executionId 调用所属的执行编号
     */
    public ToolCall(String id, String name, String argumentsJson, String userId, String executionId) {
        this(userId, executionId, null, null, name, Map.of());
    }
}
