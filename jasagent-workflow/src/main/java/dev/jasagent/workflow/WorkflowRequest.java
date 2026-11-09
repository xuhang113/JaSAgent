package dev.jasagent.workflow;

import java.nio.file.Path;

/**
 * 启动或恢复一次工作流执行时要带上的身份、检查点、输入和提示上下文。
 *
 * @param executionId 这次执行的编号，也是检查点线程编号
 * @param sessionId 会话编号
 * @param userId 用户编号
 * @param checkpointDatabase 本地检查点文件
 * @param input 用户这次的输入
 * @param workflow 要执行的工作流定义
 * @param context 角色、历史和纠正
 * @param jdbcUrl 检查点库的 JDBC 地址；空白时使用默认保存器
 */
public record WorkflowRequest(
        String executionId,
        String sessionId,
        String userId,
        Path checkpointDatabase,
        String input,
        WorkflowDefinition workflow,
        PromptContext context,
        String jdbcUrl) {
}
