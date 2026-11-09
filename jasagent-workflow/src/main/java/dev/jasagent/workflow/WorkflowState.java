package dev.jasagent.workflow;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

/**
 * 工作流图在节点之间传递的状态，键值放在父类的数据映射里。
 */
public final class WorkflowState extends AgentState {
    /**
     * 用初始键值建立图状态。
     *
     * @param initData 执行编号、输入、提示和中间结果等初始数据
     */
    public WorkflowState(Map<String, Object> initData) {
        super(initData);
    }
}
