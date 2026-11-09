package dev.jasagent.api.tool;

import dev.jasagent.api.pass.PassApproval;

import java.util.concurrent.CompletionStage;

/**
 * 工具目录。按名称找到工具，先过通行证，允许后再执行；等待审批的调用先挂起，决定下来后再继续或拒绝。
 */
public interface ToolCatalog {
    /**
     * 执行一次工具调用。未知工具失败；内部工具和 clock 直接执行；其余先申请通行证，拒绝或等待时不执行工具体。
     *
     * @param call 用户、执行、目录、工具名和参数
     * @return 完成后给出是否执行、输出和通行证的阶段
     */
    CompletionStage<ToolResult> call(ToolCall call);

    /**
     * 对先前挂起的调用作出允许或拒绝。允许时取出挂起的调用并执行；拒绝时只记下未执行。
     *
     * @param approval 用户对某张通行证的决定，含执行编号、通行证编号和用户目录
     * @return 完成后给出这次决定对应的工具结果的阶段
     */
    CompletionStage<ToolResult> completePending(PassApproval approval);
}
