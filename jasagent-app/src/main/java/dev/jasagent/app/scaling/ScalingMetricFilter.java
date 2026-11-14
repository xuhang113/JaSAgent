package dev.jasagent.app.scaling;

import dev.jasagent.app.LocalRuntime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

/**
 * 在 {@link ScalingSignals#PATH} 暴露进行中的会话和进程 CPU。
 * 这个过滤器比登录校验更早，刮取指标时不需要内部令牌，正文里也没有令牌或口令。
 */
@Component
@Order(0)
public class ScalingMetricFilter extends OncePerRequestFilter {
    private final IntSupplier sessions;

    /**
     * 用运行时的进行中会话数作为主指标。
     *
     * @param runtime 持有执行状态的运行时
     */
    @Autowired
    public ScalingMetricFilter(LocalRuntime runtime) {
        this(runtime::activeSessions);
    }

    /**
     * 用给定的计数器构造。测试不必启动整个运行时。
     *
     * @param sessions 进行中的会话数
     */
    ScalingMetricFilter(IntSupplier sessions) {
        this.sessions = sessions;
    }

    /**
     * 指标路径直接返回 Prometheus 文本。其它请求继续往后走。
     * 副作用：指标路径会写出响应并结束过滤链。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param chain 后续过滤器
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 写响应失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ScalingSignals.PATH.equals(request.getRequestURI())) {
            byte[] body = ScalingSignals.prometheus(sessions.getAsInt(), ScalingSignals.processCpu())
                    .getBytes(StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/plain; version=0.0.4; charset=utf-8");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        chain.doFilter(request, response);
    }
}
