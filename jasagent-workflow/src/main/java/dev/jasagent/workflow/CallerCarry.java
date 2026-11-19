package dev.jasagent.workflow;

import dev.jasagent.api.runtime.AssignedExecution;
import dev.jasagent.api.runtime.CallerScope;

import java.util.function.Supplier;

/**
 * 把调用线程上的租户、用户、会话和执行编号带进引擎的虚拟线程。
 * 虚拟线程不继承这些线程局部变量。任务结束时恢复进入前的值，避免串到下一次执行。
 */
final class CallerCarry {
    /**
     * 禁止实例化。快照只通过 {@link #capture()} 取得。
     */
    private CallerCarry() {
    }

    /**
     * 抄下当前线程上的身份和执行编号。
     *
     * @return 可以在引擎线程上重新绑定的快照
     */
    static Snapshot capture() {
        return new Snapshot(CallerScope.current(), AssignedExecution.current());
    }

    /**
     * 一次图执行要带上的身份和执行编号。
     */
    static final class Snapshot {
        private final CallerScope scope;
        private final String executionId;

        /**
         * 记住要带进图执行的身份和执行编号。
         *
         * @param scope 租户、用户和会话；null 表示不留身份
         * @param executionId 执行编号；空白表示不指定
         */
        private Snapshot(CallerScope scope, String executionId) {
            this.scope = scope;
            this.executionId = executionId;
        }

        /**
         * 在当前线程上绑上这份快照，算出结果后恢复进入前的身份。
         *
         * @param task 要在这份身份下计算的任务
         * @param <T> 结果类型
         * @return 任务的结果
         */
        <T> T call(Supplier<T> task) {
            CallerScope previous = CallerScope.current();
            String previousId = AssignedExecution.current();
            bind(scope, executionId);
            try {
                return task.get();
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
