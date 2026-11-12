package dev.jasagent.agentpass.starter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 代理流量的通行证过滤器。普通流量和豁免路径直接放行；代理流量先向 agentpass 校验，通过后核销，然后才进入业务。
 * 缺证、伪证、过期、已使用、用户或目标不匹配都在这里结束，不会调用后面的过滤器链。
 * 日志只写原因和路径，不写证明文。
 */
public class AgentPassFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AgentPassFilter.class);
    /**
     * 证明文无效或服务不可达时使用的状态码。调用方没有出示可用的证。
     */
    public static final int UNAUTHORIZED = 401;
    /**
     * 证能认出，但不能用于这次调用时使用的状态码。包括过期、已用和不匹配。
     */
    public static final int FORBIDDEN = 403;

    private final AgentPassClient client;
    private final AgentPassFilterProperties properties;

    /**
     * 绑定通行证客户端和本服务的流量规则。
     *
     * @param client 校验和核销客户端
     * @param properties 前缀、豁免路径、服务名和缺省绑定
     */
    public AgentPassFilter(AgentPassClient client, AgentPassFilterProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 按 {@link AgentTraffic} 分类。普通和豁免流量不访问 agentpass；代理流量必须校验成功并核销后才进入业务。
     * 副作用：拒绝时写出 JSON 并不再调用 {@code chain}；成功核销后才调用 {@code chain}。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param chain 后面的过滤器和业务
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean passPresent = request.getHeader(AgentPassHeaders.TICKET) != null
                || request.getHeader(AgentPassHeaders.PASS) != null;
        AgentTraffic.Kind kind = AgentTraffic.classify(path, request.getHeader(AgentPassHeaders.AGENT), passPresent,
                properties.getAgentPathPrefix(), properties.excludePathList());
        if (kind != AgentTraffic.Kind.AGENT) {
            chain.doFilter(request, response);
            return;
        }
        String pass = presentedPass(request);
        if (pass == null || pass.isBlank()) {
            reject(response, path, UNAUTHORIZED, "missing-pass");
            return;
        }
        PassCall call = callOf(request);
        if (call == null) {
            reject(response, path, UNAUTHORIZED, "missing-identity");
            return;
        }
        VerifyReport report;
        try {
            report = client.verify(call, pass);
        } catch (RuntimeException ex) {
            reject(response, path, UNAUTHORIZED, "unreachable");
            return;
        }
        if (!admissible(report)) {
            if (report != null && !"unreachable".equals(report.reason())) {
                try {
                    client.redeem(call, pass);
                } catch (RuntimeException ex) {
                    log.info("agent pass redeem call failed reason={}", ex.getClass().getSimpleName());
                }
            }
            reject(response, path, statusFor(report), reasonFor(report));
            return;
        }
        RemoteRedeem redeemed;
        try {
            redeemed = client.redeem(call, pass);
        } catch (RuntimeException ex) {
            reject(response, path, UNAUTHORIZED, "unreachable");
            return;
        }
        if (redeemed == null || !redeemed.redeemed()) {
            VerifyReport after = redeemed == null ? null : redeemed.report();
            reject(response, path, statusFor(after), reasonFor(after));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 取出调用方带来的证明文。优先 {@code X-Jasagent-Ticket}，没有这个头时再读 {@code X-Jasagent-Pass}。
     *
     * @param request 当前请求
     * @return 证明文；两个头都没有时为空
     */
    private static String presentedPass(HttpServletRequest request) {
        String ticket = request.getHeader(AgentPassHeaders.TICKET);
        if (ticket != null) {
            return ticket;
        }
        return request.getHeader(AgentPassHeaders.PASS);
    }

    /**
     * 从请求头和本服务配置组成校验用的绑定。目标只取配置，不信任客户端另报一个服务地址。
     * 用户、会话和执行可以不带头；运行时检索只带证明文，这些项留空表示不额外对照。
     *
     * @param request 当前请求
     * @return 可以送去校验的绑定；本服务没有配置目标时为空
     */
    private PassCall callOf(HttpServletRequest request) {
        String service = properties.getServiceName();
        if (blank(service)) {
            return null;
        }
        String user = request.getHeader(AgentPassHeaders.USER);
        String sessionId = request.getHeader(AgentPassHeaders.SESSION);
        String executionId = request.getHeader(AgentPassHeaders.EXECUTION);
        String tool = headerOrDefault(request.getHeader(AgentPassHeaders.TOOL), properties.getToolName());
        String tenant = headerOrDefault(request.getHeader(AgentPassHeaders.TENANT), properties.getTenant());
        return new PassCall(text(tenant), text(user), text(sessionId), text(executionId), service.trim(), text(tool));
    }

    /**
     * 把头的值收成去掉空白的文本。没有头时为空字符串。
     *
     * @param value 请求头或配置值
     * @return 非空文本；缺失时为空字符串
     */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 请求头有内容时用请求头，否则用配置里的缺省值。
     *
     * @param header 请求头的值
     * @param fallback 配置缺省值
     * @return 实际采用的文本，可能仍为空
     */
    private static String headerOrDefault(String header, String fallback) {
        if (header != null && !header.isBlank()) {
            return header;
        }
        return fallback;
    }

    /**
     * 判断文本是不是没有可用内容。
     *
     * @param value 待判断的文本
     * @return 空或只有空白时为真
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 判断报告是不是允许进入核销。每一项都要自己为真，不单独信任 {@code accepted}。
     *
     * @param report 校验报告
     * @return 证明有效、未使用、未过期且全部绑定匹配时为真
     */
    static boolean admissible(VerifyReport report) {
        return report != null
                && report.proofValid()
                && !report.used()
                && !report.expired()
                && report.userMatch()
                && report.targetMatch()
                && report.tenantMatch()
                && report.sessionMatch()
                && report.executionMatch();
    }

    /**
     * 把报告映射成拒绝原因。证明无效和空报告都按伪证或不可达处理。
     *
     * @param report 校验报告，可以为空
     * @return 写进响应的原因码
     */
    static String reasonFor(VerifyReport report) {
        if (report == null) {
            return "proof-invalid";
        }
        if ("unreachable".equals(report.reason())) {
            return "unreachable";
        }
        if (!report.proofValid()) {
            return "proof-invalid";
        }
        if (report.used()) {
            return "used";
        }
        if (report.expired()) {
            return "expired";
        }
        if (!report.userMatch()) {
            return "user-mismatch";
        }
        if (!report.targetMatch()) {
            return "target-mismatch";
        }
        if (!report.tenantMatch() || !report.sessionMatch() || !report.executionMatch()) {
            return "binding-mismatch";
        }
        return "rejected";
    }

    /**
     * 选择拒绝状态码。认不出证用 401，认得但不能用用 403。
     *
     * @param report 校验报告
     * @return HTTP 状态码
     */
    static int statusFor(VerifyReport report) {
        String reason = reasonFor(report);
        if ("proof-invalid".equals(reason) || "unreachable".equals(reason)) {
            return UNAUTHORIZED;
        }
        return FORBIDDEN;
    }

    /**
     * 写出拒绝正文并结束请求。不调用业务链。
     * 副作用是设置状态码和 JSON 正文，并记一条不含证明文的日志。
     *
     * @param response 当前响应
     * @param path 请求路径
     * @param status HTTP 状态码
     * @param reason 固定原因码，不含调用方输入
     */
    private void reject(HttpServletResponse response, String path, int status, String reason) throws IOException {
        log.info("agent pass rejected reason={} path={}", reason, path);
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"admitted\":false,\"reason\":\"" + reason + "\"}");
    }
}
