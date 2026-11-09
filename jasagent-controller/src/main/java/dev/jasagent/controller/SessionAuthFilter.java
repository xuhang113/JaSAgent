package dev.jasagent.controller;

import dev.jasagent.api.auth.AuthService;
import dev.jasagent.api.auth.Session;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 除健康检查、登录页和静态资源外，每个请求都要带可用的会话令牌。
 * 接口未登录时返回 401 和 AUTH，页面未登录则转到登录页。通过后把会话放进请求属性。
 */
@Component
@Order(1)
public class SessionAuthFilter extends OncePerRequestFilter {
    private final AuthService auth;

    /**
     * 注入用来校验令牌的登录服务。
     *
     * @param auth 按令牌换回会话
     */
    public SessionAuthFilter(AuthService auth) {
        this.auth = auth;
    }

    /**
     * 放行健康检查、登录页和静态资源。其余请求先取令牌并校验；没有会话时，接口写 401，页面重定向到登录页。校验通过后把会话放进请求再继续过滤器链。
     *
     * @param request 当前请求
     * @param response 用来写 401 或重定向
     * @param chain 后续过滤器和控制器
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 写响应或重定向失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/health".equals(path) || "/login".equals(path) || "/index.html".equals(path)
                || path.startsWith("/assets/") || path.startsWith("/css/") || path.startsWith("/js/")) {
            chain.doFilter(request, response);
            return;
        }
        String token = token(request);
        Session session = null;
        if (token != null) {
            try {
                session = auth.authenticate(token).toCompletableFuture().join();
            } catch (RuntimeException ignored) {
                session = null;
            }
        }
        if (session == null) {
            if (path.startsWith("/api/")) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"executionId\":null,\"errorCode\":\"AUTH\"}");
                return;
            }
            response.sendRedirect("/login");
            return;
        }
        request.setAttribute("jasagent.session", session);
        chain.doFilter(request, response);
    }

    /**
     * 先读 Authorization 里的 Bearer 令牌，没有再读名为 JASAGENT_SESSION 的 Cookie。
     *
     * @param request 当前请求
     * @return 令牌文本；两种地方都没有时为空
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
            if ("JASAGENT_SESSION".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
