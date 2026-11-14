package dev.jasagent.app.scaling;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Locale;

/**
 * 扩缩容指标。主指标是进行中的会话，CPU 只作为补充，不能单独作为扩缩容依据。
 * 本轮只负责把这两个数暴露出去，HPA 的最小 2、最大 10 由后续部署写入，不在这里改清单。
 */
public final class ScalingSignals {
    /**
     * 主指标名。Prometheus 文本和常量使用同一个名字。
     */
    public static final String PRIMARY_METRIC = "jasagent_sessions_active";
    /**
     * 补充指标名。对应进程 CPU，不能代替 {@link #PRIMARY_METRIC}。
     */
    public static final String CPU_SUPPLEMENT_METRIC = "process_cpu_usage";
    /**
     * 暴露主指标和补充指标的路径。健康检查仍走 {@code /health}。
     */
    public static final String PATH = "/health/load";

    /**
     * 禁止实例化。文本和采样都通过静态方法得到。
     */
    private ScalingSignals() {
    }

    /**
     * 读当前进程 CPU。读不到时记 0，不把负数写进指标。
     *
     * @return 0 到 1 之间的进程 CPU
     */
    public static double processCpu() {
        OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            double load = sun.getProcessCpuLoad();
            if (!Double.isNaN(load) && load >= 0 && load <= 1) {
                return load;
            }
        }
        return 0;
    }

    /**
     * 写成 Prometheus 文本。主指标在前，CPU 在后，并写明 CPU 只是补充。
     *
     * @param sessions 进行中的会话数
     * @param cpu 进程 CPU
     * @return 指标正文
     */
    public static String prometheus(int sessions, double cpu) {
        double safe = Double.isNaN(cpu) || cpu < 0 ? 0 : cpu;
        String cpuText = String.format(Locale.ROOT, "%.6f", safe);
        return "# HELP " + PRIMARY_METRIC + " 进行中的会话，扩缩容主指标。CPU 只作补充，不能当作唯一依据。\n"
                + "# TYPE " + PRIMARY_METRIC + " gauge\n"
                + PRIMARY_METRIC + " " + sessions + "\n"
                + "# HELP " + CPU_SUPPLEMENT_METRIC + " 补充指标，不能单独作为扩缩容依据。\n"
                + "# TYPE " + CPU_SUPPLEMENT_METRIC + " gauge\n"
                + CPU_SUPPLEMENT_METRIC + " " + cpuText + "\n";
    }
}
