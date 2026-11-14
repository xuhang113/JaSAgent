package dev.jasagent.gateway.proxy;

import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把浏览器的控制台请求反向代理到 jasagent-web。上游地址来自配置，网关自己不保存页面文件。
 * 上游连不上时返回错误，不回退到已删除的内嵌页面。响应和异常里不放口令或内部令牌。
 */
public final class WebConsoleProxy {
    /**
     * 上游地址的配置项。值是 jasagent-web 的根地址，例如 http://127.0.0.1:18722。
     */
    public static final String ORIGIN_PROPERTY = "jasagent.gateway.web-origin";
    /**
     * 没有单独配置时使用的本机上游。端口不是网关的 18721。
     */
    public static final String DEFAULT_ORIGIN = "http://127.0.0.1:18722";

    private final URI origin;

    /**
     * 解析上游根地址。地址必须是 http 或 https，不能在用户信息或查询串里带口令。
     *
     * @param raw 配置原文
     */
    public WebConsoleProxy(String raw) {
        this.origin = parse(raw);
    }

    /**
     * 这个请求是不是控制台路径。只有 /admin 及其子路径交给上游，登录接口和 /api 不走这里。
     *
     * @param request 当前请求
     * @return 需要反代到控制台上游时为真
     */
    public boolean console(ServerRequest request) {
        String path = request.path();
        return "/admin".equals(path) || (path != null && path.startsWith("/admin/"));
    }

    /**
     * 把请求发到配置的上游，并保留浏览器原来的路径。上游失败时返回固定的 JSON，不读取内嵌页面。
     *
     * @param request 浏览器请求
     * @param next 网关用来访问上游的处理函数
     * @return 上游的响应，或上游不可用时的错误响应
     * @throws Exception 不向外抛出上游异常；方法签名与网关过滤器一致
     */
    public ServerResponse forward(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        ServerRequest forwarded = ServerRequest.from(request).build();
        MvcUtils.setRequestUrl(forwarded, origin);
        try {
            return next.handle(forwarded);
        } catch (Exception ex) {
            return unavailable();
        }
    }

    /**
     * 实际访问的上游根地址，不含用户信息、路径和查询串。
     *
     * @return 上游根地址
     */
    public URI origin() {
        return origin;
    }

    /**
     * 返回不含口令的说明。
     *
     * @return 只标明上游根地址
     */
    @Override
    public String toString() {
        return "WebConsoleProxy{" + origin + "}";
    }

    /**
     * 把配置收成只有协议、主机和端口的地址。带了用户信息、查询串或额外路径时拒绝，避免口令混进日志。
     *
     * @param raw 配置原文
     * @return 上游根地址
     */
    private static URI parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("未配置控制台上游");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("控制台上游地址无效");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalStateException("控制台上游地址无效");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null) {
            throw new IllegalStateException("控制台上游地址不能携带口令");
        }
        String path = uri.getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            throw new IllegalStateException("控制台上游地址无效");
        }
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        return URI.create(scheme.toLowerCase() + "://" + uri.getHost() + port);
    }

    /**
     * 上游不可用时的固定错误。正文不包含页面文件名、口令或令牌。
     *
     * @return 502 JSON
     */
    private static ServerResponse unavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", null);
        body.put("errorCode", "UPSTREAM");
        body.put("message", "控制台上游不可用");
        return ServerResponse.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
