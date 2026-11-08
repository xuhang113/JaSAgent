package dev.jasagent.api.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public interface CallStats {
    void recordSuccess(String toolName);

    void recordFailure(String toolName);

    CallCount count(String toolName);

    static CallStats load() {
        List<CallStats> found = new ArrayList<>();
        ServiceLoader.load(CallStats.class, CallStats.class.getClassLoader()).forEach(found::add);
        if (found.size() != 1) {
            throw new AgentException(ErrorCode.PLUGIN, "call stats " + found.size());
        }
        return found.get(0);
    }
}
