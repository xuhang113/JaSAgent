package dev.jasagent.llm;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 启动时把 imagine-harness 环境文件里的 XAI_API_KEY 放进本进程。
 * 只读取这一项，不把文件里的其它内容留下，也不把密钥写进日志。
 */
public final class GrokEnvironment {
    /**
     * 默认的环境文件。里面的 XAI_API_KEY 只进入进程，不进入仓库。
     */
    public static final Path DEFAULT_FILE = Path.of("/Users/xuhang/Workspace/imagine-harness/.env");
    /**
     * 环境文件里的键名。
     */
    public static final String KEY = "XAI_API_KEY";

    /**
     * 禁止实例化。安装通过静态方法完成。
     */
    private GrokEnvironment() {
    }

    /**
     * 从默认环境文件安装密钥。文件不存在或没有这一项时什么都不做。
     * 副作用：可能改本进程的环境变量，并写入 {@link GrokCredentials}。
     *
     * @return 这次是否装进了一份非空密钥
     */
    public static boolean installDefault() {
        return install(DEFAULT_FILE);
    }

    /**
     * 从给定环境文件读取 XAI_API_KEY。进程环境里已经有同名非空值时不覆盖。
     * 副作用：写入进程环境和 {@link GrokCredentials}。读失败时保持原样。
     *
     * @param file 环境文件路径
     * @return 进程里现在是否有非空的 XAI_API_KEY 或已装入的密钥
     */
    public static boolean install(Path file) {
        String existing = System.getenv(KEY);
        if (existing != null && !existing.isBlank()) {
            GrokCredentials.install(existing);
            return true;
        }
        String loaded = readKey(file);
        if (loaded.isBlank()) {
            return !GrokCredentials.current().isBlank();
        }
        GrokCredentials.install(loaded);
        putProcessEnvironment(KEY, loaded);
        return true;
    }

    /**
     * 从环境文件里找出 XAI_API_KEY 的值。注释和其它键忽略。成对引号会去掉。
     *
     * @param file 环境文件；不存在时返回空串
     * @return 密钥；没有这一项时是空串
     */
    static String readKey(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String name = line.substring(0, split).trim();
                if (!KEY.equals(name)) {
                    continue;
                }
                return unquote(line.substring(split + 1).trim());
            }
        } catch (Exception ex) {
            return "";
        }
        return "";
    }

    /**
     * 去掉成对的单引号或双引号。
     *
     * @param value 等号右边的原文
     * @return 去掉外层引号后的文本
     */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * 尝试把键值写进当前进程的环境表，让 {@link System#getenv(String)} 能读到。
     * 反射失败时密钥仍留在 {@link GrokCredentials}，不把异常文本抛出去。
     *
     * @param name 环境变量名
     * @param value 环境变量值
     */
    @SuppressWarnings("unchecked")
    private static void putProcessEnvironment(String name, String value) {
        try {
            Class<?> type = Class.forName("java.lang.ProcessEnvironment");
            Field field = type.getDeclaredField("theEnvironment");
            field.setAccessible(true);
            Map<String, String> writable = (Map<String, String>) field.get(null);
            writable.put(name, value);
            try {
                Field insensitive = type.getDeclaredField("theCaseInsensitiveEnvironment");
                insensitive.setAccessible(true);
                Map<String, String> folded = (Map<String, String>) insensitive.get(null);
                folded.put(name, value);
            } catch (NoSuchFieldException ignored) {
                // 有的 JDK 只有一份环境表。
            }
        } catch (Exception ignored) {
            // 写不进进程环境时，GrokCredentials 仍然可供路由读取。
        }
    }
}
