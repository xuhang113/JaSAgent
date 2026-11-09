package dev.jasagent.app;

import dev.jasagent.cli.CliCommands;

import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * 单独启动的小探针。用反射调用命令行的基址解析，让端口测试在干净进程里看到回退地址。
 */
public final class CliBaseUrlProbe {
    /**
     * 禁止把探针当普通对象构造，只从 main 进入。
     * 副作用：没有可复用的实例。
     */
    private CliBaseUrlProbe() {
    }

    /**
     * 调用 CliCommands 的 baseUrl，把结果写到标准输出且不附加换行。
     * 副作用：把私有方法设为可访问，并向标准输出写基址。
     *
     * @param args 只有第一项，表示家目录
     */
    public static void main(String[] args) throws Exception {
        Method method = CliCommands.class.getDeclaredMethod("baseUrl", Path.class);
        method.setAccessible(true);
        Object value = method.invoke(null, Path.of(args[0]));
        System.out.print(value);
    }
}
