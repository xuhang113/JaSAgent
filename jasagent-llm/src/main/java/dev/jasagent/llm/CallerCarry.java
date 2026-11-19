package dev.jasagent.llm;

import dev.jasagent.api.runtime.AssignedExecution;
import dev.jasagent.api.runtime.CallerScope;

import java.util.function.Supplier;

/**
 * 把请求线程上的租户、用户、会话和指定执行编号抄到另一条线程。
 * 虚拟线程不会继承这些线程局部变量。移交前先 {@link #capture()} 或 {@link #hold}，到工作线程上再 {@link Snapshot#run}。
 * 工作线程结束时恢复进入前的值，避免线程复用时串到下一次调用。
 */
public final class CallerCarry {
    /**
     * 禁止实例化。快照只通过静态方法取得。
     */
    private CallerCarry() {
    }

    /**
     * 抄下当前线程上的身份和执行编号。此时不改动线程局部变量。
     *
     * @return 可以在别的线程上重新绑定的快照；当前没有身份时快照里的身份为空
     */
    public static Snapshot capture() {
        return new Snapshot(CallerScope.current(), AssignedExecution.current());
    }

    /**
     * 用已经落在执行上的身份做一份快照。工作线程看不到请求线程时，用执行自己记下的租户、用户和会话。
     *
     * @param scope 这次任务的租户、用户和会话；可以是 null
     * @param executionId 这次任务的执行编号；空白表示不指定
     * @return 绑到工作线程上的快照
     */
    public static Snapshot hold(CallerScope scope, String executionId) {
        return new Snapshot(scope, executionId);
    }

    /**
     * 一次移交要带上的身份和执行编号。
     */
    public static final class Snapshot {
        private final CallerScope scope;
        private final String executionId;

        /**
         * 记住要带过去的身份和执行编号。
         *
         * @param scope 租户、用户和会话；null 表示工作线程上不留身份
         * @param executionId 执行编号；空白表示不指定
         */
        private Snapshot(CallerScope scope, String executionId) {
            this.scope = scope;
            this.executionId = executionId;
        }

        /**
         * 在当前线程上绑上这份快照，跑完任务后恢复进入前的身份。
         * 副作用：暂时改写当前线程的 {@link CallerScope} 和 {@link AssignedExecution}。
         *
         * @param task 要在这份身份下运行的任务
         */
        public void run(Runnable task) {
            call(() -> {
                task.run();
                return null;
            });
        }

        /**
         * 在当前线程上绑上这份快照，算出结果后恢复进入前的身份。
         * 副作用：暂时改写当前线程的 {@link CallerScope} 和 {@link AssignedExecution}。
         *
         * @param task 要在这份身份下计算的任务
         * @param <T> 结果类型
         * @return 任务的结果
         */
        public <T> T call(Supplier<T> task) {
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
     * 把身份和执行编号写到当前线程。空身份和空白编号会清掉，避免留下上一次的值。
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
