package dev.jasagent.gateway.jaeger;

import dev.jasagent.auth.AccountProfile;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import dev.jasagent.gateway.route.GatewayPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 在控制台反代之前拦住 {@code /admin/jaeger} 及其子路径，按和集群页相同的登录与 admin 判断决定是否反代。
 * 上游只使用配置里的集群内地址。登录会话 Cookie 不转发出去，上游的 Set-Cookie 也不能覆盖网关会话。
 * 判断读的是会话过滤器放在请求上的账号，不在本进程里另存一份登录状态。
 */
@Component
@Order(JaegerProxyFilter.ORDER)
public class JaegerProxyFilter extends OncePerRequestFilter implements Ordered {
    /**
     * 晚于会话过滤和集群过滤。数值越小越早。
     */
    public static final int ORDER = 3;
    /**
     * 可以转给 Jaeger 的请求头。Cookie、Authorization 和转发头不在这里。
     */
    private static final Set<String> REQUEST_HEADERS = Set.of(
            "accept", "accept-language", "content-type", "user-agent", "if-none-match", "if-modified-since");
    /**
     * 可以从 Jaeger 原样带回的响应头。Set-Cookie 和 Location 另作检查。
     */
    private static final Set<String> RESPONSE_HEADERS = Set.of(
            "content-type", "cache-control", "etag", "last-modified", "content-language", "content-disposition");

    private final JaegerUpstream upstream;
    private final HttpClient client;

    /**
     * 用配置里的上游地址构造过滤器。空白表示尚未由部署填上，此时仍拦截路径，只是不反代。
     *
     * @param origin {@link JaegerUpstream#ORIGIN_PROPERTY}
     */
    @Autowired
    public JaegerProxyFilter(@Value("${" + JaegerUpstream.ORIGIN_PROPERTY + ":}") String origin) {
        this(JaegerUpstream.parse(origin));
    }

    /**
     * 使用已经解析好的上游。测试可以传入固定地址，不经过 Spring。
     *
     * @param upstream 固定上游
     */
    public JaegerProxyFilter(JaegerUpstream upstream) {
        this.upstream = upstream;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * 晚于会话过滤和集群过滤。
     *
     * @return {@link #ORDER}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * Jaeger 前缀先做和集群页相同的判断，再反代到固定上游。其它路径原样继续。
     * 未登录的页面转到 {@code /admin/login}。已登录但不是 admin 回 403，正文只有错误码。
     * 命中前缀的请求不会继续交给控制台反代。
     *
     * @param request 当前请求
     * @param response 用来写重定向、403 或上游响应
     * @param chain 后续过滤器和控制器；Jaeger 路径不会调用它
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 写响应失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!JaegerPaths.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (!(request.getAttribute(GatewaySessionFilter.ACCOUNT) instanceof AccountProfile profile)) {
            redirectToLogin(response);
            return;
        }
        if (!profile.admin()) {
            forbidden(response);
            return;
        }
        Optional<String> path = JaegerPaths.upstreamPath(request);
        if (path.isEmpty() || !upstream.enabled()) {
            if (path.isEmpty()) {
                forbidden(response);
            } else {
                unavailable(response);
            }
            return;
        }
        proxy(request, response, path.get());
    }

    /**
     * 把请求发到配置的上游，并带回状态和正文。异常时返回固定的 502，不把异常文本写出去。
     *
     * @param request 浏览器请求
     * @param response 网关响应
     * @param path 已经校验过的 Jaeger 路径
     * @throws IOException 写响应失败时抛出
     */
    private void proxy(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        URI target;
        try {
            target = upstream.target(path, request.getQueryString());
        } catch (RuntimeException ex) {
            unavailable(response);
            return;
        }
        HttpRequest upstreamRequest;
        try {
            upstreamRequest = forward(request, target);
        } catch (IOException ex) {
            unavailable(response);
            return;
        }
        try {
            HttpResponse<byte[]> upstreamResponse = client.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
            write(response, upstreamResponse);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            unavailable(response);
        } catch (IOException | RuntimeException ex) {
            unavailable(response);
        }
    }

    /**
     * 按原方法和正文组装上游请求。只复制允许的头，并去掉登录会话 Cookie。
     *
     * @param request 浏览器请求
     * @param target 固定上游上的地址
     * @return 发给 Jaeger 的请求
     * @throws IOException 读取正文失败时抛出
     */
    private static HttpRequest forward(HttpServletRequest request, URI target) throws IOException {
        String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher body = body(request, method);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(8)).method(method, body);
        if (request.getHeaderNames() != null) {
            java.util.Enumeration<String> names = request.getHeaderNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (name != null && REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    String value = request.getHeader(name);
                    if (value != null && !value.isBlank()) {
                        builder.header(name, value);
                    }
                }
            }
        }
        String cookies = cookiesWithoutSession(request);
        if (cookies != null) {
            builder.header(HttpHeaders.COOKIE, cookies);
        }
        return builder.build();
    }

    /**
     * 为带正文的方法读出请求体。GET 和 HEAD 不读正文。
     *
     * @param request 浏览器请求
     * @param method 大写的方法名
     * @return 上游请求体
     * @throws IOException 读取正文失败时抛出
     */
    private static HttpRequest.BodyPublisher body(HttpServletRequest request, String method) throws IOException {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofByteArray(request.getInputStream().readAllBytes());
    }

    /**
     * 从 Cookie 里去掉登录会话，其它 Cookie 仍按原名拼回去。没有可转发的 Cookie 时为空。
     *
     * @param request 浏览器请求
     * @return 发给 Jaeger 的 Cookie 头；只有会话 Cookie 时为空
     */
    private static String cookiesWithoutSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Cookie cookie : cookies) {
            if (cookie == null || GatewaySessionFilter.COOKIE.equalsIgnoreCase(cookie.getName())) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(cookie.getName()).append('=').append(cookie.getValue() == null ? "" : cookie.getValue());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /**
     * 把上游状态和允许的头写回浏览器。名为登录会话的 Set-Cookie 会被丢掉，指向其它主机的 Location 也不带回。
     *
     * @param response 网关响应
     * @param upstreamResponse Jaeger 的响应
     * @throws IOException 写正文失败时抛出
     */
    private static void write(HttpServletResponse response, HttpResponse<byte[]> upstreamResponse) throws IOException {
        response.setStatus(upstreamResponse.statusCode());
        upstreamResponse.headers().map().forEach((name, values) -> copyHeader(response, name, values));
        byte[] body = upstreamResponse.body();
        if (body != null && body.length > 0) {
            response.getOutputStream().write(body);
        }
    }

    /**
     * 按头的名字决定是否写回。会话 Set-Cookie 和越出前缀的 Location 不写。
     *
     * @param response 网关响应
     * @param name 上游头的名字
     * @param values 上游头的值
     */
    private static void copyHeader(HttpServletResponse response, String name, List<String> values) {
        if (name == null || values == null) {
            return;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("set-cookie".equals(lower)) {
            for (String value : values) {
                if (value != null && !sessionCookie(value)) {
                    response.addHeader(HttpHeaders.SET_COOKIE, value);
                }
            }
            return;
        }
        if ("location".equals(lower)) {
            for (String value : values) {
                if (safeLocation(value)) {
                    response.addHeader(HttpHeaders.LOCATION, value);
                }
            }
            return;
        }
        if (!RESPONSE_HEADERS.contains(lower)) {
            return;
        }
        for (String value : values) {
            if (value != null) {
                response.addHeader(name, value);
            }
        }
    }

    /**
     * 这条 Set-Cookie 是不是在写网关的登录会话。比较时忽略大小写。
     *
     * @param header Set-Cookie 的值
     * @return 名字是登录会话时为真
     */
    private static boolean sessionCookie(String header) {
        int eq = header.indexOf('=');
        String name = (eq < 0 ? header : header.substring(0, eq)).trim();
        return GatewaySessionFilter.COOKIE.equalsIgnoreCase(name);
    }

    /**
     * Location 是否仍落在 Jaeger 前缀里。绝对地址、协议相对地址和跳出前缀的点段都不带回。
     *
     * @param location 上游给出的 Location
     * @return 可以写回浏览器时为真
     */
    private static boolean safeLocation(String location) {
        if (location == null) {
            return false;
        }
        String value = location.trim();
        if (value.startsWith("//") || value.contains("://")) {
            return false;
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        return JaegerPaths.upstreamPath(value).isPresent();
    }

    /**
     * 把未登录的页面转到登录页。Location 使用相对路径。
     *
     * @param response 当前响应
     */
    private static void redirectToLogin(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, GatewayPaths.ADMIN_LOGIN);
    }

    /**
     * 拒绝非 admin 或不能安全反代的路径。正文只有错误码，不含 Jaeger 页面、口令或令牌。
     *
     * @param response 当前响应
     * @throws IOException 写正文失败时抛出
     */
    private static void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"executionId\":null,\"errorCode\":\"FORBIDDEN\"}");
    }

    /**
     * 上游没配置或暂时连不上。正文不包含上游地址、口令或令牌。
     *
     * @param response 当前响应
     * @throws IOException 写正文失败时抛出
     */
    private static void unavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"executionId\":null,\"errorCode\":\"UPSTREAM\"}");
    }
}
