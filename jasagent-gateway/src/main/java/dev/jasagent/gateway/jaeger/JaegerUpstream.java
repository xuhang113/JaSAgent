package dev.jasagent.gateway.jaeger;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 集群内 Jaeger 的固定上游。地址只来自配置，请求里的主机、转发头和查询串不能改写它。
 * 没配置时保持关闭，调用方应拒绝反代，而不是改去控制台。
 */
public final class JaegerUpstream {
    /**
     * 上游根地址的配置项。值只含协议、主机和端口，例如集群内的 ClusterIP。
     */
    public static final String ORIGIN_PROPERTY = "jasagent.gateway.jaeger-origin";

    private final URI origin;

    /**
     * 保存已经收干净的上游根地址。为空表示这次不反代。
     *
     * @param origin 只有协议、主机和端口的地址；未配置时为空
     */
    private JaegerUpstream(URI origin) {
        this.origin = origin;
    }

    /**
     * 解析配置。空白表示未配置。带了用户信息、查询串或额外路径时拒绝，避免口令混进日志。
     *
     * @param raw 配置原文
     * @return 固定上游；空白配置时为关闭状态
     */
    public static JaegerUpstream parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JaegerUpstream(null);
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Jaeger 上游地址无效");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalStateException("Jaeger 上游地址无效");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null) {
            throw new IllegalStateException("Jaeger 上游地址不能携带口令");
        }
        String path = uri.getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            throw new IllegalStateException("Jaeger 上游地址无效");
        }
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        return new JaegerUpstream(URI.create(scheme.toLowerCase() + "://" + uri.getHost() + port));
    }

    /**
     * 是否已经配置了上游。
     *
     * @return 有根地址时为真
     */
    public boolean enabled() {
        return origin != null;
    }

    /**
     * 用固定的协议、主机和端口拼出上游地址。路径必须已经落在 Jaeger 前缀里。
     * 查询串只附加在这个地址上，不能换成另一个主机。
     *
     * @param path 规范路径，必须以 {@link JaegerPaths#PREFIX} 开头
     * @param query 原始查询串，可以是 null
     * @return 指向上游的绝对地址
     */
    public URI target(String path, String query) {
        if (origin == null) {
            throw new IllegalStateException("未配置 Jaeger 上游");
        }
        if (path == null || !(JaegerPaths.PREFIX.equals(path) || path.startsWith(JaegerPaths.PREFIX + "/"))) {
            throw new IllegalStateException("Jaeger 上游路径无效");
        }
        String safeQuery = query == null || query.isBlank() ? null : query;
        try {
            URI uri = new URI(origin.getScheme(), null, origin.getHost(), origin.getPort(), path, safeQuery, null);
            if (!origin.getHost().equalsIgnoreCase(uri.getHost()) || uri.getPort() != origin.getPort()) {
                throw new IllegalStateException("Jaeger 上游地址无效");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalStateException("Jaeger 上游地址不能携带口令");
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Jaeger 上游地址无效");
        }
    }

    /**
     * 返回不含口令的说明。
     *
     * @return 未配置时标明关闭，否则只含根地址
     */
    @Override
    public String toString() {
        return "JaegerUpstream{" + (origin == null ? "disabled" : origin) + "}";
    }
}
