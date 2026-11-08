package dev.jasagent.workflow;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

public final class WorkflowState extends AgentState {
    public WorkflowState(Map<String, Object> initData) {
        super(initData);
    }
}
