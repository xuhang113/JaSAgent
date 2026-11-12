package dev.jasagent.api.exec;

import java.util.List;
import java.util.Map;

/**
 * 面向页面的执行入口。提交工作流、查看状态和日志，以及中断、恢复和检索。
 */
public interface ExecutionApi {
    /**
     * 在令牌对应的已选工作区里提交一条工作流，先记为已受理再异步启动。
     *
     * @param userId 发起执行的用户登录名
     * @param token 已登录会话的令牌，工作区取自该会话
     * @param workflowId 要运行的工作流编号
     * @param input 交给工作流的用户输入；null 按空串提交
     * @return 含 executionId 和 status 的映射，提交成功时 status 为 ACCEPTED
     */
    Map<String, Object> submit(String userId, String token, String workflowId, String input);

    /**
     * 列出这个用户的执行摘要。
     *
     * @param userId 用户登录名
     * @return 执行摘要行，每行含 executionId 和 status
     */
    List<Map<String, Object>> list(String userId);

    /**
     * 读取一条属于该用户的执行详情，包括状态、输出、队列、日志和通行证。
     *
     * @param userId 执行所属的用户登录名
     * @param executionId 执行编号
     * @return 执行详情映射；执行不存在或不属于该用户时拒绝
     */
    Map<String, Object> detail(String userId, String executionId);

    /**
     * 读取一条属于该用户的执行日志。
     *
     * @param userId 执行所属的用户登录名
     * @param executionId 执行编号
     * @return 已经积累的日志文本
     */
    String logs(String userId, String executionId);

    /**
     * 中断一条属于该用户的执行。正在运行的执行改为挂起。
     *
     * @param userId 执行所属的用户登录名
     * @param executionId 执行编号
     * @return 中断后的执行详情
     */
    Map<String, Object> interrupt(String userId, String executionId);

    /**
     * 恢复一条挂起且属于该用户的执行。不是挂起状态时直接返回当前详情。
     *
     * @param userId 执行所属的用户登录名
     * @param executionId 执行编号
     * @return 恢复动作之后的执行详情
     */
    Map<String, Object> resume(String userId, String executionId);

    /**
     * 在确认登录后按查询词检索，结果带回命中列表。
     *
     * @param userId 发起检索的用户登录名
     * @param token 已登录会话的令牌，检索前先确认会话有效
     * @param query 检索词
     * @param scopes 调用方给出的检索范围，例如 workspace
     * @return 含 reason 和 hits 的检索结果
     */
    Map<String, Object> search(String userId, String token, String query, List<String> scopes);

    /**
     * 把网关用户当前的工作区记到库里。路径必须落在允许列表中。不改登录口令，也不写登录会话。
     *
     * @param userId 网关用户 id
     * @param path 请求里的工作区路径
     * @param allowlist 允许的工作区；空列表时只接受当前目录
     * @return 含 workspace 的映射
     */
    Map<String, String> bindWorkspace(String userId, String path, List<String> allowlist);
}
