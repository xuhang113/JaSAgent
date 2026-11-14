package dev.jasagent.gateway.auth;

import dev.jasagent.api.auth.AuthService;
import dev.jasagent.api.auth.Session;
import dev.jasagent.gateway.route.GatewayPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

/**
 * 网关自己的会话过滤。未登录只放行健康检查、登录、退出、登录页和登录页静态资源。
 * 控制台业务页和 /api 必须带本网关签发的会话。校验用的是本进程读到的会话文件，不把口令或令牌写进日志。
 * 登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
 */
@Component
@Order(1)
public class GatewaySessionFilter extends OncePerRequestFilter {
    /**
     * 登录 Cookie 的名字，和原来的控制台一致。值是会话令牌，只在网关和浏览器之间使用。
     */
    public static final String COOKIE = "JASAGENT_SESSION";
    /**
     * 请求属性名。后续控制器和转发从这里取当前用户。
     */
    public static final String SESSION = "jasagent.session";

    private final AuthService auth;

    /**
     * 注入本机登录服务。它核对用户目录里的会话，不提供自助注册。
     *
     * @param auth 按令牌换回会话
     */
    public GatewaySessionFilter(AuthService auth) {
        this.auth = auth;
    }

    /**
     * 放行健康检查、登录、退出、登录页和登录页静态资源。
     * 必须有会话的 /api 没有会话时返回 401，errorCode 为 AUTH。
     * 必须有会话的页面没有会话时转到 /admin/login，不返回控制台业务正文。
     *
     * @param request 当前请求
     * @param response 用来写 401 或重定向
     * @param chain 后续过滤器和控制器
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 写响应失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        if (anonymous(method, path)) {
            chain.doFilter(request, response);
            return;
        }
        Session session = authenticate(request);
        if (session == null) {
            if (path.startsWith("/api/")) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"executionId\":null,\"errorCode\":\"AUTH\"}");
                return;
            }
            response.sendRedirect("/admin/login");
            return;
        }
        request.setAttribute(SESSION, session);
        chain.doFilter(request, response);
    }

    /**
     * 这些路径不要求已经登录。规则与 {@link GatewayPaths#anonymous} 相同，避免过滤器和路由各写一份。
     *
     * @param method 请求方法
     * @param path 请求路径
     * @return 可以匿名访问时为真
     */
    private static boolean anonymous(HttpMethod method, String path) {
        return GatewayPaths.anonymous(method, path);
    }

    /**
     * 从 Authorization 或会话 Cookie 取出令牌并核对。核对失败当作没有会话，不记录令牌。
     *
     * @param request 当前请求
     * @return 会话；没有或无效时为空
     */
    private Session authenticate(HttpServletRequest request) {
        String token = token(request);
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return auth.authenticate(token).toCompletableFuture().join();
        } catch (CompletionException ex) {
            return null;
        }
    }

    /**
     * 先读 Bearer 令牌，没有再读会话 Cookie。两种都没有时为空。
     *
     * @param request 当前请求
     * @return 会话令牌；没有时为空
     */
    private static String token(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
