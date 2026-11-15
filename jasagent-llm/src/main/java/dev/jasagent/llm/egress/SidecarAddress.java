package dev.jasagent.llm.egress;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 本进程访问 sidecar 的回环地址。离开集群的 HTTP 都发到这里的 {@code /v1/forward}。
 * 这个地址只用来找同进程组或同机的 sidecar，不能拿去向 Nacos 注册。
 */
public final class SidecarAddress {
    /**
     * 环境变量里的 sidecar 根地址，不含转发路径。不放密钥。
     */
    public static final String ENV = "JASAGENT_SIDECAR_URL";
    /**
     * 系统属性里的同一根地址。Spring 配置可以写成 {@code jasagent.sidecar.url}。
     */
    public static final String PROPERTY = "jasagent.sidecar.url";
    /**
     * 本机两台 agent 共用的 sidecar 端口。
     */
    public static final int LOCAL_PORT = 18761;
    /**
     * 没有单独配置时使用的回环根地址。
     */
    public static final String LOCAL = "http://127.0.0.1:18761";
    /**
     * sidecar 的转发路径。和 sidecar 进程的入口一致。
     */
    public static final String FORWARD_PATH = "/v1/forward";
    /**
     * 真正要访问的绝对地址。sidecar 只认这个头，不认 Host。
     */
    public static final String URL_HEADER = "X-Jasagent-Url";

    private static final AtomicReference<String> configured = new AtomicReference<>();
    private static final AtomicReference<String> testOverride = new AtomicReference<>();

    /**
     * 禁止实例化。地址只通过静态方法读取。
     */
    private SidecarAddress() {
    }

    /**
     * 记下进程配置的根地址。空白不覆盖已有配置。测试覆盖优先于这里。
     *
     * @param url sidecar 根地址，可以带末尾斜线
     */
    public static void configure(String url) {
        String cleaned = clean(url);
        if (cleaned != null) {
            configured.set(cleaned);
        }
    }

    /**
     * 测试把转发指到临时端口。不影响 {@link #configure(String)} 记下的值。
     *
     * @param url 这次测试使用的根地址
     */
    public static void useForTest(String url) {
        testOverride.set(clean(url));
    }

    /**
     * 去掉测试覆盖，后面的调用回到配置或环境。
     */
    public static void clearTest() {
        testOverride.set(null);
    }

    /**
     * 当前 sidecar 根地址，没有末尾斜线。
     *
     * @return 回环或调用方配置的根地址
     */
    public static String base() {
        String test = testOverride.get();
        if (test != null && !test.isBlank()) {
            return test;
        }
        String pinned = configured.get();
        if (pinned != null && !pinned.isBlank()) {
            return pinned;
        }
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            return trimSlash(env.trim());
        }
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            return trimSlash(property.trim());
        }
        return LOCAL;
    }

    /**
     * 转发入口的绝对地址。
     *
     * @return 根地址加上 {@link #FORWARD_PATH}
     */
    public static String forwardUrl() {
        return base() + FORWARD_PATH;
    }

    /**
     * 去掉空白和末尾斜线。空白返回空。
     *
     * @param url 原始地址
     * @return 整理后的根地址；空白时为空
     */
    private static String clean(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return trimSlash(url.trim());
    }

    /**
     * 去掉末尾多余的斜线，避免和路径拼出双斜线。
     *
     * @param url 已经去掉两端空白的地址
     * @return 没有末尾斜线的地址
     */
    private static String trimSlash(String url) {
        String value = url;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
