package dev.jasagent.llm.egress;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 判断一次 HTTP 该直连，还是交给 sidecar。回环、集群域名，以及 Nacos、MySQL、Redis、网关和其它本集群主机直连。
 * 其余 http 和 https 视为离开集群，必须走 {@code /v1/forward}。判断过程不解析公网域名，也不把口令写进日志。
 */
public final class EgressRoute {
    /**
     * 名字本身就在本集群里的主机。端口不影响判断。
     */
    private static final Set<String> NAMES = Set.of(
            "jasagent-mysql",
            "jasagent-redis",
            "jasagent-gateway",
            "jasagent-nacos",
            "nacos",
            "jasagent-agentpass",
            "jasagent-web",
            "jasagent-rag",
            "jasagent-sidecar",
            "jasagent-otel-collector",
            "jasagent-prometheus",
            "jasagent-grafana");

    /**
     * 禁止实例化。判断只通过 {@link #direct(String)} 完成。
     */
    private EgressRoute() {
    }

    /**
     * 这个地址是否不经 sidecar。非 HTTP、回环、集群后缀、已知服务名和配置里的集群主机都直连。
     *
     * @param url 绝对地址，可以是空
     * @return 应直连时为真；离开集群的 http 或 https 为假
     */
    public static boolean direct(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException ex) {
            return true;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return true;
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lower) && !"https".equals(lower)) {
            return true;
        }
        String host = normalize(uri.getHost());
        if (host == null) {
            return true;
        }
        return directHost(host);
    }

    /**
     * 用直连或 sidecar 两个词描述这次地址，方便测试确认口令没有出现在结果里。
     *
     * @param url 绝对地址
     * @return {@code direct} 或 {@code sidecar}
     */
    public static String mode(String url) {
        return direct(url) ? "direct" : "sidecar";
    }

    /**
     * 主机是否属于本进程可以直接拨的范围。
     *
     * @param host 已经小写、去掉末尾点的主机
     * @return 直连时为真
     */
    private static boolean directHost(String host) {
        if (loopback(host) || NAMES.contains(host) || clusterSuffix(host) || configured(host)) {
            return true;
        }
        return false;
    }

    /**
     * 字面回环或未指定地址。只解析数字地址，避免为了分类去查询公网 DNS。
     *
     * @param host 小写主机
     * @return 回环或未指定时为真
     */
    private static boolean loopback(String host) {
        if ("localhost".equals(host) || "localhost.localdomain".equals(host)
                || "127.0.0.1".equals(host) || "::1".equals(host) || "0.0.0.0".equals(host) || "::".equals(host)) {
            return true;
        }
        if (!numeric(host)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isAnyLocalAddress();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 判断是不是不需要 DNS 的数字地址。
     *
     * @param host 小写主机
     * @return IPv4 或 IPv6 字面量时为真
     */
    private static boolean numeric(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (!((ch >= '0' && ch <= '9') || ch == '.')) {
                return false;
            }
        }
        return !host.isEmpty();
    }

    /**
     * Kubernetes 服务域名。后缀匹配，避免把集群内服务送进 sidecar。
     *
     * @param host 小写主机
     * @return 集群域名时为真
     */
    private static boolean clusterSuffix(String host) {
        return host.endsWith(".cluster.local") || host.endsWith(".svc") || host.contains(".svc.");
    }

    /**
     * 配置里的 Nacos、JDBC、Redis 和网关主机。读的是主机名，不记录带口令的原始地址。
     *
     * @param host 小写主机
     * @return 出现在这些配置里时为真
     */
    private static boolean configured(String host) {
        if (listed(host, System.getenv("JASAGENT_NACOS_SERVER_ADDR"))
                || listed(host, System.getProperty("jasagent.nacos.server-addr"))) {
            return true;
        }
        if (listed(host, System.getenv("JASAGENT_JDBC_URL")) || listed(host, System.getProperty("jasagent.jdbc.url"))) {
            return true;
        }
        if (sameHost(host, System.getenv("JASAGENT_REDIS_HOST")) || sameHost(host, System.getProperty("jasagent.redis.host"))) {
            return true;
        }
        if (listed(host, System.getenv("JASAGENT_REDIS_URL")) || listed(host, System.getProperty("jasagent.redis.url"))) {
            return true;
        }
        if (listed(host, System.getenv("JASAGENT_GATEWAY_ORIGIN"))
                || listed(host, System.getProperty("jasagent.gateway.origin"))) {
            return true;
        }
        return listed(host, System.getenv("JASAGENT_DIRECT_HOSTS"))
                || listed(host, System.getProperty("jasagent.direct.hosts"));
    }

    /**
     * 一项配置里是否包含这个主机。多项用逗号或空白分开。
     *
     * @param host 要找的小写主机
     * @param raw 配置原文，可以是空
     * @return 某一项的主机相同则为真
     */
    private static boolean listed(String host, String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (String token : raw.split("[,\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (host.equals(hostOf(token))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 纯主机配置是否就是这个主机。
     *
     * @param host 要找的小写主机
     * @param raw 配置原文，可以是空
     * @return 相同则为真
     */
    private static boolean sameHost(String host, String raw) {
        return host.equals(hostOf(raw));
    }

    /**
     * 从主机、{@code 主机:端口} 或绝对地址里取出主机。用户信息留在地址里，不进入返回值。
     *
     * @param token 配置里的一项
     * @return 小写主机；认不出时为空串
     */
    private static String hostOf(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String rest = token.trim();
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        int query = rest.indexOf('?');
        int cut = rest.length();
        if (slash >= 0) {
            cut = Math.min(cut, slash);
        }
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        rest = rest.substring(0, cut);
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        if (rest.startsWith("[")) {
            int end = rest.indexOf(']');
            if (end > 1) {
                String host = normalize(rest.substring(1, end));
                return host == null ? "" : host;
            }
        }
        int colon = rest.lastIndexOf(':');
        if (colon > 0 && digits(rest.substring(colon + 1))) {
            rest = rest.substring(0, colon);
        }
        String host = normalize(rest);
        return host == null ? "" : host;
    }

    /**
     * 判断是不是十进制端口。
     *
     * @param value 冒号后面的文本
     * @return 非空且全是数字时为真
     */
    private static boolean digits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 收成小写主机，去掉末尾的点。空引用保持为空。
     *
     * @param host 原始主机
     * @return 小写主机；空时为空
     */
    private static String normalize(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
