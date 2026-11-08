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

@Component
@Order(1)
public class SessionAuthFilter extends OncePerRequestFilter {
    private final AuthService auth;

    public SessionAuthFilter(AuthService auth) {
        this.auth = auth;
    }

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
