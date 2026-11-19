package dev.jasagent.workflow;

import dev.jasagent.api.runtime.AssignedExecution;
import dev.jasagent.api.runtime.CallerScope;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按执行编号记住这次任务的租户、用户、会话和指定执行编号。
 * 图节点跑在调度线程上，看不见启动虚拟线程里的线程局部变量，所以进节点前按执行编号再绑一次。
 */
final class ActiveCaller {
    /**
     * 正在跑的执行。键是工作流请求里的执行编号。
     */
    private static final ConcurrentHashMap<String, Holder> ACTIVE = new ConcurrentHashMap<>();

    /**
     * 禁止实例化。登记和安装都走静态方法。
     */
    private ActiveCaller() {
    }

    /**
     * 记下这次执行要带上的身份。空执行编号时不登记。
     *
     * @param executionId 工作流请求里的执行编号
     * @param scope 启动线程上的租户、用户和会话；可以是 null
     * @param assignedExecutionId 启动线程上指定的执行编号；可以是空白
     */
    static void remember(String executionId, CallerScope scope, String assignedExecutionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        ACTIVE.put(executionId, new Holder(scope, assignedExecutionId));
    }

    /**
     * 图跑完后丢掉这次执行的身份，避免下一次同编号的任务用到旧值。
     *
     * @param executionId 工作流请求里的执行编号
     */
    static void forget(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        ACTIVE.remove(executionId);
    }

    /**
     * 把这次执行的身份绑到当前线程。没有登记时清掉，避免调度线程留下上一次的值。
     *
     * @param executionId 图状态里的执行编号
     */
    static void install(String executionId) {
        Holder holder = executionId == null ? null : ACTIVE.get(executionId);
        if (holder == null || holder.scope == null) {
            CallerScope.clear();
        } else {
            CallerScope.bind(holder.scope);
        }
        if (holder == null || holder.assignedExecutionId == null || holder.assignedExecutionId.isBlank()) {
            AssignedExecution.clear();
        } else {
            AssignedExecution.bind(holder.assignedExecutionId);
        }
    }

    /**
     * 一次执行要带上的身份。
     *
     * @param scope 租户、用户和会话
     * @param assignedExecutionId 指定的执行编号
     */
    private record Holder(CallerScope scope, String assignedExecutionId) {
    }
}
