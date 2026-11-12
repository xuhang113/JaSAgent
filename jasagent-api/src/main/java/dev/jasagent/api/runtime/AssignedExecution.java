package dev.jasagent.api.runtime;

/**
 * 网关在请求进入时指定的执行编号。
 * 控制器在调用运行时之前把它绑到当前线程，运行时用它创建执行，这样溯源和真正的执行是同一个编号。
 * 请求结束必须清掉，避免线程复用时串到下一次调用。
 */
public final class AssignedExecution {
    /**
     * 当前请求线程上的执行编号。工作线程上看不到请求线程里绑定的值。
     */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * 禁止实例化。编号只通过线程局部变量传递。
     */
    private AssignedExecution() {
    }

    /**
     * 把网关传来的执行编号绑到当前线程。空白表示这次没有指定，运行时自己生成。
     *
     * @param executionId 执行编号；空白时清掉
     */
    public static void bind(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(executionId.trim());
    }

    /**
     * 读取当前线程上的执行编号。
     *
     * @return 网关指定的编号；没有绑定时为 null
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * 去掉当前线程上的执行编号。
     * 副作用：之后 {@link #current()} 返回 null。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
