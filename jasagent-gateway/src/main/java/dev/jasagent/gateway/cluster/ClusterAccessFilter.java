package dev.jasagent.gateway.cluster;

import dev.jasagent.auth.AccountProfile;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 拒绝非 admin 访问集群接口和集群页。判断在网关里做完，不把清单转给普通用户，也不只靠页面隐藏。
 * 未登录的请求由会话过滤先挡住，这里只处理已经登录的人。
 */
@Component
@Order(ClusterAccessFilter.ORDER)
public class ClusterAccessFilter extends OncePerRequestFilter implements Ordered {
    /**
     * 晚于会话过滤，早于控制器和页面反代。数值越小越早。
     */
    public static final int ORDER = 2;
    /**
     * 集群监控接口的前缀。现有的是 {@code GET /api/cluster}，挂在这个前缀下的也不交给普通用户。
     */
    public static final String API_PREFIX = "/api/cluster";
    /**
     * 集群页。非 admin 的文档请求也在网关拒绝。
     */
    public static final String PAGE = "/admin/cluster";

    /**
     * 晚于会话过滤。
     *
     * @return {@link #ORDER}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 集群接口和集群页只放行 admin。其它路径原样继续。
     * 拒绝正文只有错误码，不含集群清单、口令或令牌。
     *
     * @param request 已经过会话过滤的请求
     * @param response 用来写 403
     * @param chain 后续过滤器和控制器
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 写响应失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!cluster(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (!(request.getAttribute(GatewaySessionFilter.ACCOUNT) instanceof AccountProfile profile) || !profile.admin()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"executionId\":null,\"errorCode\":\"FORBIDDEN\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 这条路径是不是集群监控接口或集群页。
     *
     * @param path 请求路径，不含查询串
     * @return 需要 admin 时为真
     */
    static boolean cluster(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (API_PREFIX.equals(path) || path.startsWith(API_PREFIX + "/")) {
            return true;
        }
        return PAGE.equals(path) || path.startsWith(PAGE + "/");
    }
}
