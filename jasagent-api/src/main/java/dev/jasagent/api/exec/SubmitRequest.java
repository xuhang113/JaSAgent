package dev.jasagent.api.exec;

/**
 * 提交一次执行所需的材料。关联键用来把不同会话里的相关工作排到一起。
 *
 * @param userId 发起执行的用户登录名
 * @param sessionId 这场执行所属的会话；空白时实现可以用执行编号充当会话
 * @param workspace 已选工作区的路径
 * @param workflowId 要运行的工作流编号
 * @param input 交给工作流的用户输入
 * @param correlationKey 把相关工作项归到一组的键；调度时相同且非空的键视为高度相关
 */
public record SubmitRequest(String userId, String sessionId, String workspace, String workflowId, String input, String correlationKey) {
}
