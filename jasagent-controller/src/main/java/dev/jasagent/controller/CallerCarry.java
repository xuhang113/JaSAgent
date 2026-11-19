package dev.jasagent.controller;

import dev.jasagent.api.runtime.AssignedExecution;
import dev.jasagent.api.runtime.CallerScope;

/**
 * 把当前请求的租户、用户、会话和执行编号抄到异步虚拟线程。
 * 过滤器会在控制器返回时清掉请求线程上的身份，虚拟线程必须自己带一份。
 */
final class CallerCarry {
    /**
     * 禁止实例化。快照只通过 {@link #capture()} 取得。
     */
    private CallerCarry() {
    }

    /**
     * 抄下当前请求线程上的身份和执行编号。必须在离开请求线程之前调用。
     *
     * @return 可以在虚拟线程上重新绑定的快照
     */
    static Snapshot capture() {
        return new Snapshot(CallerScope.current(), AssignedExecution.current());
    }

    /**
     * 一次异步请求要带上的身份和执行编号。
     */
    static final class Snapshot {
        private final CallerScope scope;
        private final String executionId;

        /**
         * 记住要带进虚拟线程的身份和执行编号。
         *
         * @param scope 租户、用户和会话；null 表示不留身份
         * @param executionId 执行编号；空白表示不指定
         */
        private Snapshot(CallerScope scope, String executionId) {
            this.scope = scope;
            this.executionId = executionId;
        }

        /**
         * 在当前线程上绑上这份快照，跑完任务后恢复进入前的身份。
         *
         * @param task 要在这份身份下运行的任务
         */
        void run(Runnable task) {
            CallerScope previous = CallerScope.current();
            String previousId = AssignedExecution.current();
            bind(scope, executionId);
            try {
                task.run();
            } finally {
                bind(previous, previousId);
            }
        }
    }

    /**
     * 把身份和执行编号写到当前线程。空值会清掉，避免留下上一次的值。
     *
     * @param scope 要绑定的身份；null 表示清掉
     * @param executionId 要绑定的执行编号；空白表示清掉
     */
    private static void bind(CallerScope scope, String executionId) {
        if (scope == null) {
            CallerScope.clear();
        } else {
            CallerScope.bind(scope);
        }
        if (executionId == null || executionId.isBlank()) {
            AssignedExecution.clear();
        } else {
            AssignedExecution.bind(executionId);
        }
    }
}
