package dev.jasagent.api.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 按名称累计调用的成功次数和失败次数。
 * 工具目录和插件宿主在调用真正执行之后记账；通行证拒绝或挂起时不记。
 */
public interface CallStats {
    /**
     * 把该名称的成功次数加一。
     *
     * @param toolName 要记账的工具名或插件标识
     */
    void recordSuccess(String toolName);

    /**
     * 把该名称的失败次数加一。
     *
     * @param toolName 要记账的工具名或插件标识
     */
    void recordFailure(String toolName);

    /**
     * 读取该名称当前的成功次数和失败次数。
     * 还没有记录时两路都是 0，并且不会因此新建计数。
     *
     * @param toolName 要查询的工具名或插件标识
     * @return 该名称已经累计的次数
     */
    CallCount count(String toolName);

    /**
     * 用本接口的类加载器装载唯一一份统计实现。
     * 一份都没有，或多于一份时，抛出错误码为 {@code PLUGIN} 的异常。
     *
     * @return 唯一登记的 {@link CallStats} 实现
     */
    static CallStats load() {
        List<CallStats> found = new ArrayList<>();
        ServiceLoader.load(CallStats.class, CallStats.class.getClassLoader()).forEach(found::add);
        if (found.size() != 1) {
            throw new AgentException(ErrorCode.PLUGIN, "call stats " + found.size());
        }
        return found.get(0);
    }
}
