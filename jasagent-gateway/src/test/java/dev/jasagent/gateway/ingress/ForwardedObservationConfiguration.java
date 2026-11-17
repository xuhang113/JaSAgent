package dev.jasagent.gateway.ingress;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 测试里观察转发头过滤器之后的协议。不进入生产组件扫描。
 */
@TestConfiguration
public class ForwardedObservationConfiguration {
    /**
     * 注册观察过滤器，顺序紧跟在转发头过滤器之后。
     *
     * @return 带顺序的过滤器注册
     */
    @Bean
    public FilterRegistrationBean<ForwardedObservationFilter> forwardedObservation() {
        FilterRegistrationBean<ForwardedObservationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ForwardedObservationFilter());
        registration.setOrder(ForwardedObservationFilter.ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * 把当前请求看到的协议、是否安全和转发协议抄到响应头。
     */
    static final class ForwardedObservationFilter extends OncePerRequestFilter implements Ordered {
        /**
         * 晚于转发头过滤器，早于登录表单。数值越小越早。
         */
        static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;
        /**
         * 观察到的协议。
         */
        static final String SCHEME = "X-Observed-Scheme";
        /**
         * 观察到的是否安全。值是 true 或 false。
         */
        static final String SECURE = "X-Observed-Secure";
        /**
         * 观察到的 X-Forwarded-Proto。被丢掉时是空串。
         */
        static final String PROTO = "X-Observed-Forwarded-Proto";
        /**
         * 转发头过滤器是否已经跑过。
         */
        static final String APPLIED = "X-Observed-Applied";
        /**
         * 套接字对端，用来核对信任判断看的是不是这一跳。
         */
        static final String REMOTE = "X-Observed-Remote";

        /**
         * 返回固定顺序。
         *
         * @return {@link #ORDER}
         */
        @Override
        public int getOrder() {
            return ORDER;
        }

        /**
         * 先写下观察结果，再交给后面的登录和跳转。此时转发头过滤器应该已经包好请求。
         *
         * @param request 当前请求
         * @param response 当前响应
         * @param chain 后面的过滤器
         */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader(SCHEME, request.getScheme());
            response.setHeader(SECURE, Boolean.toString(request.isSecure()));
            String proto = request.getHeader("X-Forwarded-Proto");
            response.setHeader(PROTO, proto == null ? "" : proto);
            response.setHeader(APPLIED, String.valueOf(request.getAttribute(TrustedForwardedHeaderFilter.APPLIED)));
            response.setHeader(REMOTE, request.getRemoteAddr());
            chain.doFilter(request, response);
        }
    }
}
