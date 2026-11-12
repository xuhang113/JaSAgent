package dev.jasagent.llm;

/**
 * 启动时放进本进程的 Grok 密钥。只留在内存里，{@link #toString()} 不包含密钥。
 * 环境变量里已经有密钥时，路由优先用环境变量。
 */
public final class GrokCredentials {
    /**
     * 进程内的密钥。空串表示还没有从环境文件装入。
     */
    private static volatile String key = "";

    /**
     * 禁止实例化。密钥通过静态方法安装和读取。
     */
    private GrokCredentials() {
    }

    /**
     * 记下一份密钥。空白值忽略，避免把空配置盖掉已经装好的密钥。
     * 副作用：之后 {@link #current()} 返回这份密钥。
     *
     * @param value 从环境文件读出的 XAI_API_KEY，可以是空白
     */
    public static void install(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        key = value.trim();
    }

    /**
     * 返回已经装入的密钥。
     *
     * @return 密钥；还没装入时是空串
     */
    public static String current() {
        String value = key;
        return value == null ? "" : value;
    }

    /**
     * 返回不含密钥的说明。
     *
     * @return 是否已经装入密钥
     */
    @Override
    public String toString() {
        return "GrokCredentials[installed=" + !current().isBlank() + "]";
    }
}
