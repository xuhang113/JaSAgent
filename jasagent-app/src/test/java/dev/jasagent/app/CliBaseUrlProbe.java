package dev.jasagent.app;

import dev.jasagent.cli.CliCommands;

import java.lang.reflect.Method;
import java.nio.file.Path;

public final class CliBaseUrlProbe {
    private CliBaseUrlProbe() {
    }

    public static void main(String[] args) throws Exception {
        Method method = CliCommands.class.getDeclaredMethod("baseUrl", Path.class);
        method.setAccessible(true);
        Object value = method.invoke(null, Path.of(args[0]));
        System.out.print(value);
    }
}
