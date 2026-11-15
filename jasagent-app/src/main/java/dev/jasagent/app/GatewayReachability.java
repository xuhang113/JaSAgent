package dev.jasagent.app;

import dev.jasagent.llm.egress.SidecarForward;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 判断 AI Gateway 的基址是否还能连上。同一地址 30 秒内复用上一次结论，避免每次列模型都打一次网。
 * 本集群和回环地址直连；离开集群的地址经 sidecar，sidecar 拒绝不算连上上游。
 */
final class GatewayReachability implements SessionModelCatalog.GatewayProbe {
    /**
     * 连接和探测请求共用的 2 秒上限。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final SidecarForward client = SidecarForward.open(TIMEOUT, true);
    private volatile String cachedUrl = "";
    private volatile boolean cachedReachable;
    private volatile long cachedAt;

    /**
     * 基址为空时直接不可达。否则在 30 秒缓存内返回上次结果，过期后重新探测并记下地址、结论和时间。
     *
     * @param baseUrl Gateway 基址，允许首尾空白
     * @return 能对这个地址发出 HTTP 请求并收到任何响应时为 true
     */
    @Override
    public boolean reachable(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String url = baseUrl.trim();
        long now = System.currentTimeMillis();
        if (url.equals(cachedUrl) && now - cachedAt < 30_000) {
            return cachedReachable;
        }
        boolean open = probe(url);
        cachedUrl = url;
        cachedReachable = open;
        cachedAt = now;
        return open;
    }

    /**
     * 对基址发一次 GET。没有协议或主机时不发请求。中断时恢复中断标记。
     * 直连时任何 HTTP 状态都算连上；经 sidecar 时，未放行不算连上上游。连接失败才算不可达。
     *
     * @param baseUrl 已去掉首尾空白的基址
     * @return 收到响应时为 true
     */
    private boolean probe(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            try (SidecarForward.Call response = client.exchange("GET", uri, Map.of(), new byte[0], TIMEOUT)) {
                String text = response.text();
                if (response.rejected(text)) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }
}
