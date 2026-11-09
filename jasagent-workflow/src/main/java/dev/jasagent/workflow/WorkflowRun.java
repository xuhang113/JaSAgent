package dev.jasagent.workflow;

import java.util.List;

/**
 * 一次工作流执行的结果：状态、输出、错误、图描述、插件错误和检查点实现。
 *
 * @param executionId 这次执行的编号
 * @param status 执行状态，例如 SUCCEEDED、FAILED、SUSPENDED
 * @param output 成功时的输出文本
 * @param errorCode 失败时的错误码名字；成功时为空
 * @param graphDsl 编译后的图描述
 * @param pluginErrors 插件失败但节点继续时记下的错误
 * @param evaluationPassed 评估节点写下的是否通过；没有评估时为 null
 * @param correctionSources 这次执行里纠正笔记的来源
 * @param checkpointSaver 实际使用的检查点保存器类名
 * @param detail 失败时的异常链文本
 * @param thinking 模型留下的思考文本；没有时为空串
 */
public record WorkflowRun(
        String executionId,
        String status,
        String output,
        String errorCode,
        String graphDsl,
        List<String> pluginErrors,
        Boolean evaluationPassed,
        List<String> correctionSources,
        String checkpointSaver,
        String detail,
        String thinking) {
    /**
     * 思考文本缺失时收成空串。
     *
     * @param executionId 执行编号，这里原样保留
     * @param status 执行状态，这里原样保留
     * @param output 输出文本，这里原样保留
     * @param errorCode 错误码名字，这里原样保留
     * @param graphDsl 图描述，这里原样保留
     * @param pluginErrors 插件错误，这里原样保留
     * @param evaluationPassed 评估结论，这里原样保留
     * @param correctionSources 纠正来源，这里原样保留
     * @param checkpointSaver 检查点保存器类名，这里原样保留
     * @param detail 失败细节，这里原样保留
     * @param thinking 思考文本；null 时改成空串
     */
    public WorkflowRun {
        if (thinking == null) {
            thinking = "";
        }
    }
}
