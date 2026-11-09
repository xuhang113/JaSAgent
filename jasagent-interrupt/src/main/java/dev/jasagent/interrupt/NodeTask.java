package dev.jasagent.interrupt;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** 工作流里的一个节点：有稳定编号，并按执行编号异步产出一段文本。 */
public final class NodeTask {
    private final String id;
    private final Function<String, CompletionStage<String>> action;

    /**
     * 记下节点编号和收到执行编号后要跑的动作。编号是否合法留到流程打开时再查。
     *
     * @param id 节点编号
     * @param action 输入执行编号、输出一段文本的异步动作
     */
    private NodeTask(String id, Function<String, CompletionStage<String>> action) {
        this.id = id;
        this.action = action;
    }

    /**
     * 组装一个节点。编号和动作的合法性留到流程打开时再查。
     *
     * @param id 节点编号
     * @param action 输入执行编号、输出一段文本的异步动作
     * @return 新节点
     */
    public static NodeTask of(String id, Function<String, CompletionStage<String>> action) {
        return new NodeTask(id, action);
    }

    /**
     * 返回节点编号。恢复时用它在流程里定位下一步。
     *
     * @return 节点编号
     */
    public String id() {
        return id;
    }

    /**
     * 用这条执行的编号跑节点动作。
     *
     * @param executionId 执行编号
     * @return 节点产出的文本；动作返回空阶段时由调用方判失败
     */
    CompletionStage<String> run(String executionId) {
        return action.apply(executionId);
    }
}
